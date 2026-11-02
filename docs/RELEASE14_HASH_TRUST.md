# ZIP14: Source metadata must match Tracker's catalog SHA-256

- `FILE_SOURCES_RESPONSE` now carries `fileHash` from the Tracker DB (`files.file_hash`, READY rows only).
- The JavaFX file and folder download paths reject missing/different Tracker hashes and pass the canonical hash to the transfer engine.
- Peer metadata whose fileHash/geometry conflicts with this trusted value is rejected BEFORE any piece is written. Final full-file SHA-256 is still checked.
- Legacy test/emulator overloads of `downloadFile` have no catalog hash, so these entry points still require integration review. The network trust anchor is meaningful only with authenticated Tracker transport (TLS); unencrypted LAN can be MITM-modified.
- This is a protocol data-shape change. Tracker and all Peer clients MUST be upgraded together before testing. The unchanged protocol version in legacy source means mixed-version compatibility is **not** guaranteed.
- Manually test wrong metadata, one malicious Peer plus a good Peer, multi-source, 0-byte, >2GB, folder resume, and SHA-256 of final output. No real LAN tests were run during patch generation.
