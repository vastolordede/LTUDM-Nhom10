# Install the authenticated Tracker's PUBLIC bootstrap without copying any private key.
# Required only for the first launch of a trusted desktop distribution; packagers can
# bundle this script with CLIENT_PUBLIC_BOOTSTRAP and invoke it automatically.
param(
    [Parameter(Mandatory = $true)][string]$BootstrapDirectory
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $BootstrapDirectory).Path
$profile = Join-Path $env:USERPROFILE '.fileshare-p2p'
foreach ($name in @('.env','certs\peer-truststore.p12','certs\tracker-public.key.b64')) {
    if (-not (Test-Path (Join-Path $source $name) -PathType Leaf)) {
        throw "Untrusted/incomplete public bootstrap: missing $name"
    }
}
if (Test-Path (Join-Path $profile '.env')) {
    Write-Host 'Trusted Tracker bootstrap already installed; preserving existing identity and configuration.'
    exit 0
}
New-Item -ItemType Directory -Force -Path (Join-Path $profile 'certs') | Out-Null
Copy-Item -LiteralPath (Join-Path $source '.env') -Destination (Join-Path $profile '.env')
foreach ($name in @('peer-truststore.p12','tracker-public.key.b64')) {
    Copy-Item -LiteralPath (Join-Path $source "certs\$name") -Destination (Join-Path $profile "certs\$name")
}
$me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $profile '/inheritance:r' '/T' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect Peer profile ACL' }
& icacls.exe $profile '/grant:r' "${me}:(OI)(CI)F" '/T' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect Peer profile ACL' }
Write-Host 'Tracker trust bootstrap installed. Peer identity will be generated at first login.'
