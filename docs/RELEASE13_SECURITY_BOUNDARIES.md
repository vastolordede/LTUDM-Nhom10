# ZIP13 security boundaries / limitations

- **PRODUCTION** enforces TLS enabled for Tracker and P2P and requires P2P hostname verification; Peer certificates need SANs matching the IP/DNS used in connections. `P2P_PROFILE=production` on Tracker and each Peer.
- `P2P_PROFILE=lan` forbids demo bypass, but can use plain TCP only on trusted LAN for compatibility testing. **LAN plain TCP is not encrypted**.
- `P2P_TLS_VERIFY_HOSTNAME` now defaults TRUE when P2P TLS is enabled. An explicitly disabled check is a **development/LAN exception only** and is not suitable for public Internet.
- P2P bitfield and piece requests require a Tracker-signed ticket plus an Ed25519 proof-of-possession signed by the requester's registered private key; the proof binds request type, uploader, file, piece, ticket hash, timestamp, and nonce. The timestamp window is 60 seconds. Uploaders keep a bounded in-memory replay guard; it is cleared on uploader restart, while the timestamp window limits the replay exposure. Metadata-page requests remain ticket-authorized because they return metadata only, not piece bytes.
- R76 does NOT implement R77 ticket revocation or refresh. A stolen ticket alone cannot retrieve piece data, but a stolen ticket together with the requester's private key can be used until the ticket expires (up to 60 minutes).
- SHA-256 ensures integrity but not confidentiality. TLS protects transport when configured correctly. Certificate trust material and private keys must never be shipped in public builds.
- Run negative test cases: wrong Tracker key, missing Peer public key, invalid certificate SAN, wrong peer, expired ticket, restart and transfer over LAN/Relay; review logs for leaks.
