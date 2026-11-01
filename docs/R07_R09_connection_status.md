# R07/R09 - Direct, NAT and relay indicators

Dashboard now constructs RendezvousClient from the **active** TrackerConnection
configuration selected on Login (R53), rather than reloading .env and silently
switching to an old host or TLS truststore. It shows reachable Direct vs
NAT/firewall probe failure on both window title and toolbar, and whether Relay
was requested or disabled. The UI deliberately does **not** claim Relay
connected merely because its agent was started.

Remaining acceptance: test LAN, public Direct, NAT blocked + actual Relay,
expired Tracker session, TLS mismatch and reconnect on Windows/real networks.
No Internet connection or Router/NAT acceptance was performed offline.
