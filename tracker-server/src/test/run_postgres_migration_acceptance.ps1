$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$containerName = 'fileshare-pg-acceptance-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$randomBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($randomBytes)
$password = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$rng.Dispose()

$previousUrl = $env:P2P_PG_TEST_URL
$previousUser = $env:P2P_PG_TEST_USER
$previousPassword = $env:P2P_PG_TEST_PASSWORD
$containerStarted = $false

try {
    docker run --detach --rm --name $containerName --publish '127.0.0.1::5432' `
        --env "POSTGRES_PASSWORD=$password" --env 'POSTGRES_DB=fileshare_acceptance' postgres:16-alpine
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not start disposable PostgreSQL container.'
    }
    $containerStarted = $true

    $portMapping = docker port $containerName '5432/tcp'
    if ($LASTEXITCODE -ne 0 -or -not $portMapping) {
        throw 'Could not determine the dynamically assigned PostgreSQL port.'
    }
    $port = [int](($portMapping -split ':')[-1])

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $ready = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            & docker exec $containerName psql -U postgres -d fileshare_acceptance -Atc 'SHOW server_version' *> $null
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
    Write-Output "Docker image: postgres:16-alpine"
    Write-Output "PostgreSQL server version: $serverVersion"

    $env:P2P_PG_TEST_URL = "jdbc:postgresql://127.0.0.1:$port/fileshare_acceptance"
    $env:P2P_PG_TEST_USER = 'postgres'
    $env:P2P_PG_TEST_PASSWORD = $password
    Push-Location $repoRoot
    try {
        .\gradlew.bat --no-daemon :tracker-server:test --tests vn.edu.p2p.tracker.config.PostgresMigrationIntegrationTest
        if ($LASTEXITCODE -ne 0) {
            throw 'PostgreSQL migration integration test failed.'
        }
    } finally {
        Pop-Location
    }
} finally {
    if ($null -eq $previousUrl) { Remove-Item Env:P2P_PG_TEST_URL -ErrorAction SilentlyContinue }
    else { $env:P2P_PG_TEST_URL = $previousUrl }
    if ($null -eq $previousUser) { Remove-Item Env:P2P_PG_TEST_USER -ErrorAction SilentlyContinue }
    else { $env:P2P_PG_TEST_USER = $previousUser }
    if ($null -eq $previousPassword) { Remove-Item Env:P2P_PG_TEST_PASSWORD -ErrorAction SilentlyContinue }
    else { $env:P2P_PG_TEST_PASSWORD = $previousPassword }
    if ($containerStarted) {
        docker rm --force --volumes $containerName *> $null
    }
}
