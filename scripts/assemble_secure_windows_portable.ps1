param(
    [Parameter(Mandatory=$true)][string]$TrustedBootstrapDirectory,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$bootstrap = (Resolve-Path -LiteralPath $TrustedBootstrapDirectory).Path
$target = [System.IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path $target) { throw "Output already exists: $target" }
Push-Location $root
try {
    & .\gradlew.bat :peer-client:installDist
    if ($LASTEXITCODE -ne 0) { throw 'Gradle installDist failed' }
    New-Item -ItemType Directory -Path $target | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $target 'scripts') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $target 'peer-client\build\install') | Out-Null
    Copy-Item -Recurse -LiteralPath (Join-Path $root 'peer-client\build\install\peer-client') -Destination (Join-Path $target 'peer-client\build\install') -Force
    Copy-Item -Recurse -LiteralPath $bootstrap -Destination (Join-Path $target 'CLIENT_PUBLIC_BOOTSTRAP') -Force
    Copy-Item -LiteralPath (Join-Path $root 'scripts\install_secure_peer_windows.ps1') -Destination (Join-Path $target 'scripts')
    Copy-Item -LiteralPath (Join-Path $root 'scripts\run_secure_peer_windows.cmd') -Destination (Join-Path $target 'scripts')
    Write-Host "Portable distribution ready: $target\scripts\run_secure_peer_windows.cmd"
} finally { Pop-Location }
