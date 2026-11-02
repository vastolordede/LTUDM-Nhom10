# ZIP16: LAN / Relay integrity and stress acceptance

Changes: TLS handshake failures no longer fall back to Relay (authentication must not downgrade); RelayAgent does not abandon its reconnect loop on one saturated acceptor queue; Tracker prunes stale rendezvous-probe markers.

Manual gate on 2+ machines (NOT executed in patch generation):

1. Use one LAN/hotspot, real Ed25519 keys, real Peer client and Tracker; choose unique peer listening ports; verify IP/firewall and Tracker TCP reachability.
2. Single-source: 1–10 MiB and SHA-256 exact.
3. Three-peer: A and B share same hash, C downloads multi-source; disconnect A mid-download; C resumes or retries B.
4. Switch Tracker IP, restart Tracker, verify heartbeat/reconnect and no duplicate ONLINE sessions.
5. TLS: wrong hostname/certificate must fail, and must not silently move to plaintext or masked Relay connection.
6. Internet: verify public IPv4 + NAT/CGNAT constraints; Direct when reachable, otherwise Relay; check VPS bandwidth and per-session Relay caps. A hotspot private IP does NOT prove Internet reachability.
7. Stress: 100–1000 virtual peers are protocol/resource tests; not equivalent to 1000 physical PCs or PB-scale file transfer.

Remaining security limitation: bearer tickets are not requester-PoP-bound and cannot be instantly revoked on logout. Review before public deployment.
