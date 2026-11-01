# R53 / R44 / R59 - Client Tracker settings and diagnostics

The login screen can save IP/domain, TCP port, TCP/TLS mode and truststore path
in `~/.fileshare-p2p/tracker-settings.properties` without editing `.env`.
Secrets (truststore passwords, ticket signing/private keys) are NOT persisted.
TLS password is still sourced from the existing env / local `.env` configuration.

Save changes only while logged out. The client closes the previous Tracker socket
and constructs a fresh authenticated connection; test the new TCP/TLS handshake
using the `Test connection` button before login. The diagnostic deliberately
never sends username/password. A successful TCP probe is not a successful login.

Regression: invalid host/port, TLS enabled without truststore password, wrong TLS
port, expired/unknown CA certificate, Tracker restart, save/restart application,
read-only local configuration and stale socket. Check screen layout at 100% and
125% Windows DPI scaling. Not tested in a GUI runtime in this environment.
