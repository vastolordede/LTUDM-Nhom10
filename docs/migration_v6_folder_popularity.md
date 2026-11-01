# Migration v6 - R67: folder popularity

Run `database/migration_v6.sql` on a **backup-tested** PostgreSQL instance after applying v2 through v5. This migration is additive; it does not rewrite existing transfer history. It introduces `folder_download_sessions` with an idempotent UUID key so repeat reports from one completed folder attempt count once. Existing historical folder-download counts start at zero; **do not backfill from file-transfer counts**, which do not prove a whole-folder download.

1. Inspect production/staging database schema and back up first; do not run changes automatically.
2. Apply migration_v6.sql as an approved DB change.
3. Verify table, FK references, index, and rerun migration to confirm idempotency.
4. Check a folder download: partial, cancelled, or failed attempts do not increment. Completing all selected folder files in the `Tải cả thư mục` flow increments once; repeating the report with the same UUID does not increment twice.

Note: Tracker trusts the authenticated Peer to assert completion, just as existing transfer-history reports do. This is not cryptographic proof of possession; keep this in the security audit backlog (R18/R43).
