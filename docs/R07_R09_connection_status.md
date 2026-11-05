# R07/R09 - Direct, NAT and relay indicators

Dashboard constructs RendezvousClient from the **active** TrackerConnection
configuration selected on Login (R53), rather than reloading .env and silently
switching to an old host or TLS truststore. Login and Dashboard read one
session-scoped connectivity model for Tracker, LAN/Internet Direct, and Relay.
The model receives Tracker login/heartbeat results, the bounded NAT probe,
actual ConnectionPlanner routes, RelayAgent registration/retry events, and
relay-socket close events. Status listeners marshal display updates to the
JavaFX thread and are removed during Login shutdown or Dashboard logout/close.
Relay is shown as ready only after Rendezvous registration, in use while a
relay-backed transfer socket is active, and returned to ready when its final
socket closes. TLS identity failures remain terminal and are never reported as
Relay fallback. Error descriptions use the safe R44 diagnostic classification.

Remaining acceptance: test LAN, public Direct, NAT blocked + actual Relay,
expired Tracker session, TLS mismatch and reconnect on Windows/real networks.
No Internet connection or Router/NAT acceptance was performed offline.
