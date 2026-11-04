[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Backup', 'Restore')]
    [string]$Operation,

    [string]$BackupPath,

    [switch]$AllowNonEmpty,

    [string]$DockerContainer
)

$ErrorActionPreference = 'Stop'

function Get-RequiredSetting([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Missing required environment variable: $Name"
    }
    return $value
}

function ConvertTo-ShellArgument([string]$Value) {
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Invoke-PostgresTool([string]$Tool, [string[]]$Arguments) {
    if ($DockerContainer) {
        $quotedArguments = @($Tool) + $Arguments |
            ForEach-Object { ConvertTo-ShellArgument "$_" }
        $dockerPassword = 'PGPASSWORD=' + (Get-RequiredSetting 'PGPASSWORD')
        $output = & docker exec --env $dockerPassword $DockerContainer sh -c `
            ('exec ' + ($quotedArguments -join ' ')) 2>&1
        $exitCode = $LASTEXITCODE
    } else {
        $command = Get-Command $Tool -ErrorAction SilentlyContinue
        if ($null -eq $command) {
            throw "$Tool is not installed or is not available in PATH."
        }
        $output = & $command.Source @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    if ($exitCode -ne 0) {
        $details = ($output | ForEach-Object { "$_" }) -join [Environment]::NewLine
        throw "$Tool failed with exit code $exitCode. $details"
    }
    return $output
}

function Get-ConnectionArguments {
    if ($DockerContainer) {
        return @("--host=$script:databaseHost", "--port=$script:databasePort",
            "--username=$script:databaseUser", "--dbname=$script:databaseName", '--no-password')
    }
    return @("--host=$script:databaseHost", "--port=$script:databasePort",
        "--username=$script:databaseUser", "--dbname=$script:databaseName", '--no-password')
}

function Test-DatabaseEmpty {
    $query = @"
SELECT NOT EXISTS (
    SELECT 1
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
      AND n.nspname NOT LIKE 'pg_%'
      AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
)
"@
    $arguments = (Get-ConnectionArguments) + @('--tuples-only', '--no-align',
        '--set=ON_ERROR_STOP=1', "--command=$query")
    $result = Invoke-PostgresTool 'psql' $arguments
    return (($result -join '').Trim() -eq 't')
}

function Get-BackupFile([bool]$ForBackup) {
    if ([string]::IsNullOrWhiteSpace($BackupPath)) {
        if (-not $ForBackup) {
            throw 'BackupPath is required for Restore.'
        }
        $backupDirectory = Join-Path $HOME 'fileshare-backups'
        [void](New-Item -ItemType Directory -Force -Path $backupDirectory)
        $name = 'fileshare_{0}_{1}.dump' -f (Get-Date -Format 'yyyyMMdd_HHmmss'),
            ([Guid]::NewGuid().ToString('N').Substring(0, 8))
        $BackupPath = Join-Path $backupDirectory $name
    }

    $fullPath = [IO.Path]::GetFullPath($BackupPath)
    if ($ForBackup) {
        if (Test-Path -LiteralPath $fullPath) {
            throw "Backup output already exists: $fullPath"
        }
        [void](New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($fullPath)))
    }
    return $fullPath
}

$script:databaseName = Get-RequiredSetting 'PGDATABASE'
$script:databaseUser = Get-RequiredSetting 'PGUSER'
$null = Get-RequiredSetting 'PGPASSWORD'
if ($script:databaseName -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
    throw 'PGDATABASE must be a simple PostgreSQL identifier.'
}
if ($script:databaseUser -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
    throw 'PGUSER must be a simple PostgreSQL role name.'
}

if ($DockerContainer) {
    if ($DockerContainer -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') {
        throw 'DockerContainer has an invalid container name.'
    }
}
$script:databaseHost = Get-RequiredSetting 'PGHOST'
$script:databasePort = Get-RequiredSetting 'PGPORT'
if ($script:databasePort -notmatch '^\d+$' -or
        [int]$script:databasePort -lt 1 -or [int]$script:databasePort -gt 65535) {
    throw 'PGPORT must be between 1 and 65535.'
}
$null = Get-RequiredSetting 'PGPASSWORD'

if ($Operation -eq 'Backup') {
    if ($AllowNonEmpty) {
        throw 'AllowNonEmpty applies only to Restore.'
    }
    $destination = Get-BackupFile $true
    $temporaryPath = "$destination.partial"
    $containerTemporaryPath = '/tmp/fileshare-backup-' + [Guid]::NewGuid().ToString('N') + '.dump'
    try {
        $arguments = @('--format=custom', '--no-owner', '--no-acl', '--file')
        if ($DockerContainer) {
            $arguments += $containerTemporaryPath
        } else {
            $arguments += $temporaryPath
        }
        $arguments += Get-ConnectionArguments
        $null = Invoke-PostgresTool 'pg_dump' $arguments

        if ($DockerContainer) {
            & docker cp "${DockerContainer}:$containerTemporaryPath" $temporaryPath
            if ($LASTEXITCODE -ne 0) {
                throw 'Could not copy the completed backup from the PostgreSQL container.'
            }
        }
        if (-not (Test-Path -LiteralPath $temporaryPath) -or
                (Get-Item -LiteralPath $temporaryPath).Length -le 0) {
            throw 'pg_dump did not create a non-empty backup.'
        }
        $listPath = if ($DockerContainer) { $containerTemporaryPath } else { $temporaryPath }
        $null = Invoke-PostgresTool 'pg_restore' @('--list', $listPath)
        Move-Item -LiteralPath $temporaryPath -Destination $destination
        Write-Output ("Backup created: {0} ({1} bytes)" -f
            [IO.Path]::GetFileName($destination), (Get-Item -LiteralPath $destination).Length)
    } finally {
        Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
        if ($DockerContainer) {
            & docker exec $DockerContainer rm -f $containerTemporaryPath *> $null
        }
    }
    return
}

if ([string]::IsNullOrWhiteSpace($BackupPath)) {
    throw 'BackupPath is required for Restore.'
}
$archive = [IO.Path]::GetFullPath($BackupPath)
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
    throw "Backup file does not exist: $archive"
}
if ((Get-Item -LiteralPath $archive).Length -le 0) {
    throw 'Backup file is empty.'
}

$containerArchivePath = '/tmp/fileshare-restore-' + [Guid]::NewGuid().ToString('N') + '.dump'
try {
    if ($DockerContainer) {
        & docker cp $archive "${DockerContainer}:$containerArchivePath"
        if ($LASTEXITCODE -ne 0) {
            throw 'Could not copy the backup into the PostgreSQL container.'
        }
        $null = Invoke-PostgresTool 'pg_restore' @('--list', $containerArchivePath)
    } else {
        $null = Invoke-PostgresTool 'pg_restore' @('--list', $archive)
    }

    $connectionArguments = Get-ConnectionArguments
    $null = Invoke-PostgresTool 'psql' ($connectionArguments +
            @('--set=ON_ERROR_STOP=1', '--command=SELECT 1'))
    if (-not $AllowNonEmpty -and -not (Test-DatabaseEmpty)) {
        throw 'Restore target is not empty. Use a new empty database, or explicitly pass -AllowNonEmpty.'
    }

    $restoreArguments = @('--exit-on-error', '--single-transaction', '--no-owner', '--no-acl')
    if ($AllowNonEmpty) {
        $restoreArguments += @('--clean', '--if-exists')
    }
    $restoreArguments += Get-ConnectionArguments
    if ($DockerContainer) {
        $restoreArguments += $containerArchivePath
    } else {
        $restoreArguments += $archive
    }
    $null = Invoke-PostgresTool 'pg_restore' $restoreArguments
    Write-Output ("Restore completed into database: {0}" -f $script:databaseName)
} finally {
    if ($DockerContainer) {
        & docker exec $DockerContainer rm -f $containerArchivePath *> $null
    }
}
