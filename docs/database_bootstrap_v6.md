# PostgreSQL schema and migration order (R12-R14, R47)

> **Review before running anything on a database with real data.** The application does not apply migrations automatically. The statements below are for an empty *dedicated* database; preserve your current DB and make a backup first.

## New empty development database

Open CMD/PowerShell in the repository root. With `psql` configured and connected to the desired database:

```text
psql -v ON_ERROR_STOP=1 -f database/schema.sql
psql -v ON_ERROR_STOP=1 -f database/migration_v2.sql
psql -v ON_ERROR_STOP=1 -f database/migration_v3.sql
psql -v ON_ERROR_STOP=1 -f database/migration_v4.sql
psql -v ON_ERROR_STOP=1 -f database/migration_v5.sql
psql -v ON_ERROR_STOP=1 -f database/migration_v6.sql
psql -v ON_ERROR_STOP=1 -f database/migration_v7.sql
psql -v ON_ERROR_STOP=1 -f database/migration_v8.sql
psql -v ON_ERROR_STOP=1 -f database/migration_v9.sql
psql -v ON_ERROR_STOP=1 -f database/verify_schema.sql
```

The `schema.sql` file creates the foundational tables/views, v2 adds publish status and aliases, v3 filters non-READY sources, v4 adds folders, v5 repairs eligible legacy metadata, and v6 adds completed-folder events; v7 requires completed child-transfer evidence; v8 preserves empty nested directories; v9 adds the nullable Peer public key. `verify_schema.sql` is the finalization step: it fails on missing schema objects or unrepaired publish metadata, then records `schema_version = 9`. It is safe to rerun after successful migrations.

## Existing / populated database

**DO NOT run the full sequence blindly:** v2 and v4 include non-idempotent DDL. Inspect `information_schema`, `to_regclass`, and prior migrations. Export a backup, coordinate schema changes with the team, apply only missing migrations **in order**, then execute `verify_schema.sql`. A database without the version marker is not accepted by Tracker. The marker is written only after required relations, columns, indexes, constraints, v3 source filtering, and the v5 data-repair invariant pass. The checksum manifest verifies SQL source integrity, not the applied migration history. Never publish DB credentials or backups to Git.

Tracker startup uses `DatabaseConnectionFactory.verify()` and fails fast unless the required relations exist and `schema_version` is verified at version 9. This marker records a successfully verified schema state; it is not a per-migration history ledger. Use the ordered scripts and verifier rather than inferring applied migrations from the source checksum manifest.

## Additional manual acceptance

- Verify startup against a staging copy of an existing DB after applying missing migrations only.
- Confirm production backup/restore and rollback procedures independently; they are outside this migration test.
- Check Search/Download and folder workflows after the schema upgrade.
- Check `file_aliases`, `file_groups`, `group_files`, `folder_download_sessions`, indexes, `v_file_sources` and sample Search/Download.
- Protocol 3 login registers the Peer Ed25519 public key; apply migration v9 and have existing Peers log in again before requesting sources.

## Backup, restore, and rollback (R55)

Install PostgreSQL client tools (`pg_dump`, `pg_restore`, and `psql`) on the operator
machine and provide `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD`
through the current process environment or an approved secret manager. The script
never accepts a password argument or writes it to a file or log. Its default backup
location is `$HOME/fileshare-backups`, outside the repository. Keep backups encrypted
and access-restricted; never commit them.

```powershell
.\tools\postgres_backup_restore.ps1 -Operation Backup
.\tools\postgres_backup_restore.ps1 -Operation Restore -BackupPath 'D:\secure-backups\fileshare.dump'
```

Backups use PostgreSQL custom format. Restore validates the archive and database
connection before changing the target, and refuses a non-empty database by default.
Restore into a newly-created empty database for routine recovery. `-AllowNonEmpty`
is an explicit destructive confirmation that asks `pg_restore` to clean objects
included in the archive; it is not a substitute for an exact rollback because objects
created after the backup may not be present in that archive.

There are **no down migrations**. To roll back, stop Tracker, restore the pre-upgrade
backup into a separate empty database, set Tracker's `DB_NAME` to that database, and
run the matching previous Tracker build. Run `database/verify_schema.sql` and confirm
its version is supported by that build before starting Tracker. Do not expect the
current v9 Tracker to accept a pre-v9 snapshot.

For a small deployment, take a backup daily while the service is in use and before
every schema change. Keep a documented rotation (for example, 7 daily and 4 weekly
copies) on protected storage separate from the database host. At least monthly, restore
a copy to a disposable database, compare important row counts/records, and run
`database/verify_schema.sql`; record the result and restore duration. Rehearse a full
Tracker restart against the restored database before relying on the backup.

The real PostgreSQL recovery integration can be run on Windows with Docker:

```powershell
.\tracker-server\src\test\run_postgres_recovery_acceptance.ps1
```

It creates a uniquely named disposable `postgres:16-alpine` container with a random
process-only password and dynamic localhost port, and removes that container and its
anonymous volume on completion. It does not use or inspect existing database volumes.
