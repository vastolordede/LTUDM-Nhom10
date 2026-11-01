# LAST PR - optional staging VPS infrastructure (not deployed)

**Not for immediate use:** this compose file is an offline-reviewed template,
not a proven production deployment. Complete all core tests, configure TLS,
review DB migrations and receive team approval **before** VPS deployment.

Uses a private-only PostgreSQL network with no published 5432 port; Tracker
ports 9000 (TLS) and 9001 (Rendezvous/Relay, TLS) are published. Java 21
runtime runs non-root inside Tracker. Never put passwords, TLS private keys,
or Ed25519 private keys in Git or a ZIP patch. Create
`infra/staging/local-secrets/` privately on the VPS with both `tracker.p12`
and the base64 text containing a PKCS8-DER Ed25519 private key in
`tracker-signing.key.b64` (no sample real keys provided). Preserve the
corresponding PUBLIC verifier key securely on every Peer.

**First-time empty DB only** (manually on staging, not run by Compose):

1. Provide `P2P_STAGING_DB_PASSWORD` and `P2P_STAGING_TLS_PASSWORD` in the
   VPS secret manager or a private, untracked local environment.
2. Start only DB: `docker compose -f infra/staging/compose.yml up -d postgres`.
3. From repo root, pipe **each** `database/schema.sql`, then `migration_v2.sql`
   through `migration_v8.sql` into `docker compose ... exec -T postgres
   psql -v ON_ERROR_STOP=1 -U fileshare -d fileshare_p2p`. Stop immediately
   on an error. Run `database/verify_schema.sql` before first Tracker boot.
4. `docker compose -f infra/staging/compose.yml up --build -d tracker`.
   Preserve logs, inspect TLS handshake, firewall and restart policy.

**Existing DB:** do not rerun schema/migrations. Backup using `pg_dump -Fc`
into a private directory with restricted permissions, rehearse `pg_restore`
into a separate disposable DB, compare schema/data and test rollback.
Do not expose backup files over HTTP or commit them. On rollback, restore
previous tracker version and **compatible DB snapshot** together; older
binaries may not support new columns. Keep each migration/backup aligned
with the actual merged PR IDs in the checklist.

Critical E2E before public exposure: trustworthy certificate/SAN on Tracker
and Peer, wrong truststore refusal, two-network Direct/NAT/Relay with 3 Peers,
large-file SHA-256, rate limiting, permissions, auth ticket replay review,
service outage and automated backup/restore. VPS IP, domain, credentials,
certificates, resource quotas and firewall rules are user/environment-specific
and deliberately NOT invented here.
