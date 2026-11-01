# R67 - completed folder ranking evidence

Migration v7 creates `folder_download_evidence`. Each successful full-folder
completion must contain one distinct file transfer UUID for each file path,
ordered as returned by the folder detail API (relative_path ordering). Tracker
checks that every transfer belongs to the reporting peer, references the
expected file ID, reports COMPLETED and has bytes_received >= file_size.

Two different folder completions cannot reuse one file transfer UUID. Replaying
the same group_transfer_id returns `recorded=false` without double counting.
Stale/missing/FAILED transfers do not count. Existing locally verified files
create their own new COMPLETED history row so a repeat full-folder download can
be counted once as a new operation. Client reports alone still cannot prove
that a malicious peer downloaded bytes; cryptographic receipts would require
new protocol architecture. This is a stronger Tracker-side consistency check,
not Byzantine security.

Run v7 only after backing up/staging v6. Re-run schema verification and
confirm idempotency/replay/unauthorized-peer tests on PostgreSQL. Do not apply
v7 automatically with a ZIP patch.
