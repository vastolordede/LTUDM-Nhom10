# Release gate / manual sign-off

Base: `LTUDM-Nhom10-dev.zip` supplied on 2026-10-30.

## Evidence checklist

- [ ] `scripts\predeploy-check.cmd` exits 0 (save console log)
- [ ] PostgreSQL `database/verify_schema.sql`: missing tables=none, ready_files_missing_pieces=0
- [ ] Metadata page failure/resume verified; final READY and Search
- [ ] 3 real peers successfully transfer (LAN, SHA-256 match)
- [ ] 3 real peers test disconnect, retry, partial seeding, resume
- [ ] Two networks test direct if NAT permits; relay otherwise
- [ ] TLS Tracker and Peer actual handshake verified on both sides
- [ ] Folder share/selective download and symlink defenses verified
- [ ] No real `.env`, `.p12`, `.jks`, `.pem`, tokens or credentials tracked
- [ ] All patch commits exist with truthful `git log --oneline` hashes
- [ ] One final push to `fix/predeploy-stabilization`, one PR against `dev`

The checks above intentionally start unchecked. Do not convert to PASS until evidence is collected.
