# Run from repository root on Windows 10/11 with JDK 21 jpackage in PATH.
$ErrorActionPreference = 'Stop'
$lib = 'peer-client/build/install/peer-client/lib'
$jars = @(Get-ChildItem -Path $lib -Filter 'peer-client-*.jar' -File -ErrorAction SilentlyContinue)
if ($jars.Count -ne 1) {
    throw 'Expected one versioned peer-client JAR. Run: .\gradlew.bat :peer-client:installDist'
}
$mainJar = $jars[0].Name
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw 'jpackage not found (requires full JDK 21)'
}
New-Item -Type Directory -Force dist | Out-Null
if (Test-Path 'dist/FileShare-P2P') {
    throw 'dist/FileShare-P2P already exists. Rename/remove previous app-image manually before packaging.'
}
$version = '0.1.0'
if ($env:GITHUB_REF -match '^refs/tags/v(\d+\.\d+\.\d+)$') {
    $version = $Matches[1]
}
& jpackage --type app-image --name FileShare-P2P `
  --input $lib --main-jar $mainJar `
  --main-class vn.edu.p2p.peer.PeerLauncher `
  --dest dist --app-version $version
if ($LASTEXITCODE -ne 0) { throw "jpackage failed: $LASTEXITCODE" }
Compress-Archive -Force -Path 'dist/FileShare-P2P' -DestinationPath 'dist/FileShare-P2P-windows.zip'
Write-Host 'Created: dist/FileShare-P2P-windows.zip'
