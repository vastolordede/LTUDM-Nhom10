# ZIP11: Build/DB safety gate

Baseline: exported origin/dev 8deb836942743d701f83b415da25e11f549b17c6.

1. Verify Java 21 is available (`java -version`); configure `JAVA_HOME` when needed.
2. Gradle wrapper uses LF on Unix, while `.bat` uses CRLF on Windows.
3. SQL checksum manifest detects drift; it **does not execute migrations** or know the migration history of an existing database.
4. For populated DBs: stop Tracker, run `pg_dump`, restore to a disposable DB, inspect `information_schema`, apply ONLY missing v2..v8 migrations in order, run `database/verify_schema.sql`; never blindly replay non-idempotent DDL.
5. Baseline/test requirements: `python tools/check_repo_markers.py`, `python tools/check_db_migrations.py`, `python tools/check_fxml_bindings.py`, `python tools/check_release_hygiene.py`, `gradlew.bat clean test build` on Windows.
6. Windows source checkout may contain CRLF in existing files: use the hash-checked line-ending helper only when a ZIP explicitly needs it.

This patch does not modify any live database or any server.
