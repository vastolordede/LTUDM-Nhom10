# ZIP13 security boundaries / limitations

- **PRODUCTION** enforces TLS enabled for Tracker and P2P and requires P2P hostname verification; Peer certificates need SANs matching the IP/DNS used in connections. `P2P_PROFILE=production` on Tracker and each Peer.
- `P2P_PROFILE=lan` forbids demo bypass, but can use plain TCP only on trusted LAN for compatibility testing. **LAN plain TCP is not encrypted**.
- `P2P_TLS_VERIFY_HOSTNAME` now defaults TRUE when P2P TLS is enabled. An explicitly disabled check is a **development/LAN exception only** and is not suitable for public Internet.
- Ed25519 tickets are bearer tokens valid for up to 60 minutes. This patch does NOT establish cryptographic proof of requester identity or immediate ticket revocation after logout/unshare; peer client certificates are presently not tied to requester peer IDs. Do NOT claim full protection against stolen-ticket replay. Full solution requires requester-bound asymmetric key proof or mTLS identity mapping plus revocation state and compatibility tests.
- SHA-256 ensures integrity but not confidentiality. TLS protects transport when configured correctly. Certificate trust material and private keys must never be shipped in public builds.
- Run negative test cases: wrong Tracker key, missing Peer public key, invalid certificate SAN, wrong peer, expired ticket, restart and transfer over LAN/Relay; review logs for leaks.
