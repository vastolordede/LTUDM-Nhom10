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
psql -v ON_ERROR_STOP=1 -f database/verify_schema.sql
```

The `schema.sql` file creates the foundational tables/views, v2 adds publish status and aliases, v3 filters non-READY sources, v4 adds folders, v5 repairs eligible legacy metadata, and v6 adds idempotent completed-folder events.

## Existing / populated database

**DO NOT run the full sequence blindly:** v2 and v4 include non-idempotent DDL. Inspect `information_schema`, `to_regclass`, and prior migrations. Export a backup, coordinate schema changes with the team, apply only missing migrations **in order**, then execute `verify_schema.sql`. Never publish DB credentials or backups to Git.

Tracker startup uses `DatabaseConnectionFactory.verify()` and now fails fast if mandatory relations or `files.publish_status` are absent. This protects the JavaFX app from silent protocol/database errors, but it does not replace a full migration history table or database transaction rehearsal.

## Manual acceptance (not performed in ChatGPT)

- Run on an empty DB: setup and verifier pass.
- Run on a staging copy of an existing DB: apply missing migrations only; preserve data.
- Remove v6 in a disposable test DB: Tracker must refuse to start with a useful error.
- Check `file_aliases`, `file_groups`, `group_files`, `folder_download_sessions`, indexes, `v_file_sources` and sample Search/Download.
