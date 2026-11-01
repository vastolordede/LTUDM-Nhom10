# FileShare-P2P - project readme update proposal (R33)

## Main modules

`common`: DTO, framing, metadata and ticket verification. `tracker-server`:
user authentication, heartbeat, PostgreSQL catalog, source discovery, transfer
history and NAT/Relay rendezvous. `peer-client`: JavaFX GUI, share/search,
folder discovery, piece upload and multithread/multisource download.

Java 21, Gradle 8.10.2, PostgreSQL, JavaFX 21. The Tracker stores metadata,
**not file bytes**. The Peer upload service must be reachable directly or via
an explicitly configured Relay. Files may exceed 2 GiB; validation requires
real storage/network acceptance testing.

## Local dev setup

1. Create a **dedicated** PostgreSQL database; apply `database/schema.sql`
   then `database/migration_v2.sql` ... `migration_v8.sql` sequentially *only
   if not yet applied*. Run `database/verify_schema.sql` and preserve backups.
2. Configure Tracker DB/TLS and Ed25519 signing key on the server; configure
   the PUBLIC ticket verification key on uploading Peers. Do not publish keys.
3. Run `gradle :tracker-server:run`; `gradle :peer-client:run` on distinct
   terminals, using a disposable test account.
4. Register, login, share, Explore/search/sort, download, history and logout.
5. For CI/release: see `docs/R48_R54_windows_package_and_ci.md` and
   `docs/R58_R51_E2E_ACCEPTANCE.md`.

## Limitations

Private P2P sessions rely on bounded bearer tickets, not a complete security
model; credentials and host identities need real TLS validation. File/folder
popularity depends on reported transfer history; untrusted client reports
need further adversarial testing. Global file/folder pagination is separate.
Internet NAT, Relay and large-file acceptance tests are still pending.

This is an **update proposal** rather than a blind overwrite of a potentially
existing root README in the user's GitHub checkout. Merge it after reviewing
that file on the user's machine.
