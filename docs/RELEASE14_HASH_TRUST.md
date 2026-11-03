# ZIP14: Source metadata must match Tracker's catalog SHA-256

## Protocol 3 Peer-key registration (R76 PR 2)

Protocol 3 adds the Peer Ed25519 X.509 public key to the authenticated LOGIN
request. Apply database migration v9 (`peers.public_key`) before deploying the
Tracker. Each successful login stores/replaces that Peer ID's public key; source
tickets include the requester's registered key. Upgrade Tracker and Peers
together. Existing peers must log in again before requesting sources; they are
rejected with `REQUESTER_KEY_MISSING` until a key is registered.

- `FILE_SOURCES_RESPONSE` now carries `fileHash` from the Tracker DB (`files.file_hash`, READY rows only).
- The JavaFX file and folder download paths reject missing/different Tracker hashes and pass the canonical hash to the transfer engine.
- Peer metadata whose fileHash/geometry conflicts with this trusted value is rejected BEFORE any piece is written. Final full-file SHA-256 is still checked.
- Legacy test/emulator overloads of `downloadFile` have no catalog hash, so these entry points still require integration review. The network trust anchor is meaningful only with authenticated Tracker transport (TLS); unencrypted LAN can be MITM-modified.
- Protocol version 3: Tracker and Peer upload/download clients must be upgraded together; older clients/servers are rejected with `UNSUPPORTED_PROTOCOL_VERSION`.
- Manually test wrong metadata, one malicious Peer plus a good Peer, multi-source, 0-byte, >2GB, folder resume, and SHA-256 of final output. No real LAN tests were run during patch generation.
