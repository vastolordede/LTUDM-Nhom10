# R01-R06: metadata completion, page retries, crash recovery

Existing database-backed page updates lock the parent files row, validate
index range/last-piece length/hash using PiecePageGuard, reject conflicting
replays and commit each batch transactionally. A COMPLETE count plus unique
(file_id, piece_index) proves the indexes cover the full range. This patch
adds a scheduled hourly reconciliation at Tracker startup: complete
PUBLISHING records become READY, and metadata orphaned for 7+ days may be
pruned only when no shares, history or group reference remains. Partial
records with active sharing peers are retained for missing-page replay.

`PiecePageLargeFileTest` checks logical metadata geometry above 2 GiB and
2000 pieces without allocating a massive file. This is **not** an actual
>2GB transfer test: run R06/R51 on machines with enough disk, verify real
SHA-256 and preserve logs. SQL reconciliation has not run on live Postgres.
