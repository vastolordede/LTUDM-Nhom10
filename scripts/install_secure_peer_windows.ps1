# Install a PRE-PROVISIONED, unique Peer TLS profile for the current Windows 10 user.
# Example: powershell -ExecutionPolicy Bypass -File .\scripts\install_secure_peer_windows.ps1 -PeerProfileDirectory "D:\release\PEERS\peer001"
# Run this only with a package from a verified administrator. The package is a secret.
param(
    [Parameter(Mandatory = $true)][string]$PeerProfileDirectory
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $PeerProfileDirectory).Path
$profile = Join-Path $env:USERPROFILE '.fileshare-p2p'
if (-not (Test-Path (Join-Path $source '.env') -PathType Leaf)) {
    throw 'Peer package missing .env'
}
foreach ($name in @('peer-p2p.p12','peer-p2p-truststore.p12','peer-truststore.p12','tracker-public.key.b64')) {
    if (-not (Test-Path (Join-Path $source "certs\$name") -PathType Leaf)) {
        throw "Peer package missing certs\$name"
    }
}
if (Test-Path $profile) {
    throw "Profile already exists: $profile. Never overwrite keys silently; use an administrator-approved rotation procedure."
}
New-Item -ItemType Directory -Path $profile | Out-Null
Copy-Item -LiteralPath (Join-Path $source '.env') -Destination (Join-Path $profile '.env')
Copy-Item -LiteralPath (Join-Path $source 'certs') -Destination (Join-Path $profile 'certs') -Recurse
# Windows ACL: remove inherited group/world access and grant only current user.
$me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $profile '/inheritance:r' '/T' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not remove inherited ACLs; stop installation' }
& icacls.exe $profile '/grant:r' "${me}:(OI)(CI)F" '/T' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not grant private user ACL; stop installation' }
Write-Host "Trusted Peer TLS profile installed at $profile"
Write-Host 'Start FileShare-P2P from your usual launcher, then sign up/sign in. No TLS checkbox is needed.'
