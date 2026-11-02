# ZIP15: Stored share safety / zero-length file support

- A corrupt persisted `~/.fileshare-p2p/*-shared.json` must never be overwritten by Add/Remove. `load()` remains non-throwing for UI compatibility, but modification now fails with an IOException until the file is repaired or deliberately moved aside by its owner.
- On restore, files up to 32 MiB with canonical SHA-256 are hashed again, detecting modifications that keep size unchanged. Large-file restores still rely on existing per-piece upload SHA-256 validation to avoid blocking startup for TB-sized files. This is NOT a complete integrity proof of all large shares at startup.
- An empty file has zero pieces; the downloader now creates/truncates an empty target before final SHA-256. Exercise zero-byte, same-size modification, path symlink and retry scenarios.
- Outstanding deeper folder and catalog acceptance remains manual until a multi-machine run; these changes are not proof that every old R26–R28/R46/R51 has passed.
