$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$containerName = 'fileshare-pg-recovery-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$randomBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($randomBytes)
$password = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$rng.Dispose()

$previous = @{
    P2P_PG_TEST_URL = $env:P2P_PG_TEST_URL
    P2P_PG_TEST_USER = $env:P2P_PG_TEST_USER
    P2P_PG_TEST_PASSWORD = $env:P2P_PG_TEST_PASSWORD
    P2P_PG_TEST_CONTAINER = $env:P2P_PG_TEST_CONTAINER
    P2P_PG_TEST_DOCKER_HOST = $env:P2P_PG_TEST_DOCKER_HOST
}
$containerCreated = $false

try {
    $portProbe = New-Object System.Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $portProbe.Start()
    $hostPort = $portProbe.LocalEndpoint.Port
    $portProbe.Stop()
    docker run --detach --name $containerName --publish "127.0.0.1:${hostPort}:5432" `
        --env "POSTGRES_PASSWORD=$password" --env 'POSTGRES_DB=fileshare_acceptance' postgres:16-alpine
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not start disposable PostgreSQL recovery container.'
    }
    $containerCreated = $true

    $portMapping = docker port $containerName '5432/tcp'
    if ($LASTEXITCODE -ne 0 -or -not $portMapping) {
        throw 'Could not determine the dynamically assigned PostgreSQL port.'
    }
    $port = [int](($portMapping -split ':')[-1])
    if ($port -ne $hostPort) {
        throw 'PostgreSQL container did not bind the selected available localhost port.'
    }
    $containerAddress = docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $containerName
    if ($LASTEXITCODE -ne 0 -or -not $containerAddress) {
        throw 'Could not determine the disposable PostgreSQL container network address.'
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $ready = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            docker exec $containerName psql -U postgres -d fileshare_acceptance -Atc 'SHOW server_version' *> $null
            if ($LASTEXITCODE -eq 0) {
                $ready = $true
                break
            }
            Start-Sleep -Seconds 1
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if (-not $ready) {
        throw 'Disposable PostgreSQL did not become ready within 60 seconds.'
    }
    $serverVersion = docker exec $containerName psql -U postgres -d fileshare_acceptance -Atc 'SHOW server_version'
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not read the disposable PostgreSQL server version.'
    }
    Write-Output 'Docker image: postgres:16-alpine'
    Write-Output "PostgreSQL server version: $serverVersion"

    $env:P2P_PG_TEST_URL = "jdbc:postgresql://127.0.0.1:$port/fileshare_acceptance"
    $env:P2P_PG_TEST_USER = 'postgres'
    $env:P2P_PG_TEST_PASSWORD = $password
    $env:P2P_PG_TEST_CONTAINER = $containerName
    $env:P2P_PG_TEST_DOCKER_HOST = $containerAddress
    Push-Location $repoRoot
    try {
        .\gradlew.bat --no-daemon :tracker-server:test --tests vn.edu.p2p.tracker.config.PostgresRecoveryIntegrationTest
        if ($LASTEXITCODE -ne 0) {
            throw 'PostgreSQL backup/restore recovery integration test failed.'
        }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $previous.Keys) {
        if ($null -eq $previous[$name]) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
        }
    }
    if ($containerCreated) {
        docker rm --force --volumes $containerName *> $null
    }
}
