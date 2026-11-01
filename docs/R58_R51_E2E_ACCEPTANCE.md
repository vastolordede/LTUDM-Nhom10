# R58/R51: acceptance evidence, mapped to every manual PR

The sheet says the course expects **10 functional and 7 technical
requirements**, but their original verbatim wording was not included in the
handed-off checklist. Do not invent the teacher's requirements: the 17 entries
in `R58_17_REQUIREMENTS_EVIDENCE.csv` are placeholders until the official
assignment text is supplied. Mark PASS only after obtaining reproducible logs.

## Core automated checks (PR CI)

`gradle clean check build` on JDK 21; FXML controllers; secret filename scan;
metadata geometry and retry, auth and session, signed P2P tickets, share,
search and transfer tests. Capture CI URL, JUnit XML and failing screenshots.

## Mandatory real-machine tests (NOT executed)

- R06/R51: 2.1+ GiB file and >2000 pieces: share, find, download, SHA-256 both ends.
- R20/R21: 3 distinct Peers on 2 machines with 2 sources, partial seeding during transfer.
- R22/R23: terminate downloader, restart, validate resume, disconnect source,
  timeout/retry/failover, final file SHA-256.
- R24/R25: corrupt piece, blacklist, readonly output, disk full, duplicate file names.
- R26/R27/R46: multi-level empty folder, zero-byte file, symlink, traversal
  input, selective download/rebuild hierarchy, cancel/restart.
- R28/R67: same content different filename, re-share after restart, file/folder
  COMPLETED history idempotency and source ranking.
- R07/R08/R09/R44: GUI login -> dashboard -> logout -> login again;
  NAT/direct/relay status, mismatched TLS and recovery.
- R29/R30: run concurrent TrafficEmulator with 3+ Peers, preserve throughput,
  upload-source breakdown, CPU/memory, timeout, error metrics.
- R48/R60: clean Windows 10/11 VM without installed JDK, app packaging,
  upgrade preserving identity and resume state.
- R35/R49/R50/R55 (last): staging VPS, private DB, firewalls, backups,
  two Internet networks, relay fallback, restore/rollback drills.

Attach evidence to the **actual PR number/URL**, not the commit SHA alone.
Never store credentials, private keys, personal IPs or database dumps in PRs.
