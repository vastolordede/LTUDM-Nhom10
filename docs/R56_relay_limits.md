# R56 - Relay quotas (pre-deployment)

Relay is a **fallback**, not a file repository. R56 bounds per-session bytes,
throughput across both directions, duration, concurrent sessions, and socket idle time.
No single relay session may exceed the configured cap. Files larger than 2 GiB
can now traverse Relay if max-session GiB and max-seconds allow it; formerly
64 MiB exhausted the entire stream.

Environment variables (defaults):
- `P2P_RELAY_MAX_CONNECTIONS=32` (1..128)
- `P2P_RELAY_MAX_SESSION_GIB=8` (1..1024)
- `P2P_RELAY_MAX_MIB_PER_SECOND=8` (1..1000, per session both directions)
- `P2P_RELAY_MAX_SESSION_SECONDS=7200` (60..86400)

Each `CONNECT` takes an available slot; the two copy workers use one shared
RelayBudget and close both sockets after copy ends. The Tracker may still be
resource-constrained under parallel sessions: harden at OS firewall and
reverse-proxy/network levels. Live NAT, TLS and throughput benchmarks needed.
