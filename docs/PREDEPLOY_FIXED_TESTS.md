# Predeploy fix acceptance gates (NOT automatically passed)

All patches in this series apply in order to the exact dev source archive supplied on 2026-10-30.
Their local Git commit hashes are generated only after a successful run on the user's machine.
No Java, PostgreSQL, LAN or cloud end-to-end test was executed in the authoring container.

## 1. Build (Windows CMD in the project root)

```cmd
.\gradlew.bat clean test
.\gradlew.bat build
```

Do not commit if either command exits nonzero.

## 2. DB (local PostgreSQL; backup existing database first)

On a fresh DB: schema.sql then migration_v2.sql through migration_v5.sql.
On an existing DB: only migrations that have NOT already been applied, in order.
Then run `psql -X -v ON_ERROR_STOP=1 -U postgres -d fileshare_p2p -f database/verify_schema.sql`.
Verify `ready_files_missing_pieces = 0`.

## 3. Mandatory A1 functional cases

1. Login/Logout and heartbeat on two user accounts.
2. Publish, Search, Unshare, and duplicate SHA with two names.
3. Share a file with 2,001+ pieces; after the final metadata page it must be READY and searchable.
4. Interrupt publishing midway: it must remain PUBLISHING and invisible until resumed.
5. Repeat a metadata page: identical replay accepted; conflicting piece hash rejected.
6. A and B share the same content; C downloads from both concurrently; verify final SHA-256.
7. Interrupt source A; pending pieces reassign to source B and final SHA matches.
8. Partial seeding: C uploads a verified piece while still downloading.
9. Upload/download a folder with nested paths and selective download.
10. GUI logout closes folder tasks and relay channel; login again cleanly.

## 4. Security / deployment blockers

- On the public Tracker, both Tracker control and Rendezvous sockets require TLS.
- P2P TLS certificate/truststore must work for real Peer↔Peer connections.
- Tracker TLS cert SAN must match TRACKER_HOST; P2P hostname verification requires peer SAN when enabled.
- Probe/Direct/Relay require live tests on TWO physical networks: NAT results cannot be proved by code alone.
- Check that PostgreSQL port 5432 is not exposed publicly and no secrets are staged for commit.
- The optional TrafficEmulator test does not replace the real three-Peer GUI demo.

Do not mark an A1/Sheet row PASS until automated and appropriate manual acceptance have passed.
