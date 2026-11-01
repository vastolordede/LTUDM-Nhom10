# Run from repository root on Windows 10/11 with JDK 21 jpackage in PATH.
$ErrorActionPreference = 'Stop'
$lib = 'peer-client/build/install/peer-client/lib'
if (-not (Test-Path "$lib/peer-client.jar")) {
    throw 'Missing installDist. Run: gradle :peer-client:installDist'
}
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw 'jpackage not found (requires full JDK 21)'
}
New-Item -Type Directory -Force dist | Out-Null
$version = '0.1.0'
if ($env:GITHUB_REF -match '^refs/tags/v(\d+\.\d+\.\d+)$') {
    $version = $Matches[1]
}
& jpackage --type app-image --name FileShare-P2P `
  --input $lib --main-jar peer-client.jar `
  --main-class vn.edu.p2p.peer.PeerApplication `
  --dest dist --app-version $version
if ($LASTEXITCODE -ne 0) { throw "jpackage failed: $LASTEXITCODE" }
Compress-Archive -Force -Path 'dist/FileShare-P2P' -DestinationPath 'dist/FileShare-P2P-windows.zip'
Write-Host 'Created: dist/FileShare-P2P-windows.zip'
