# ZIP14: Source metadata must match Tracker's catalog SHA-256

- `FILE_SOURCES_RESPONSE` now carries `fileHash` from the Tracker DB (`files.file_hash`, READY rows only).
- The JavaFX file and folder download paths reject missing/different Tracker hashes and pass the canonical hash to the transfer engine.
- Peer metadata whose fileHash/geometry conflicts with this trusted value is rejected BEFORE any piece is written. Final full-file SHA-256 is still checked.
- Legacy test/emulator overloads of `downloadFile` have no catalog hash, so these entry points still require integration review. The network trust anchor is meaningful only with authenticated Tracker transport (TLS); unencrypted LAN can be MITM-modified.
- Protocol version 2: Tracker and Peer upload/download clients must be upgraded together; version-1 clients/servers are rejected with `UNSUPPORTED_PROTOCOL_VERSION`.
- Manually test wrong metadata, one malicious Peer plus a good Peer, multi-source, 0-byte, >2GB, folder resume, and SHA-256 of final output. No real LAN tests were run during patch generation.
