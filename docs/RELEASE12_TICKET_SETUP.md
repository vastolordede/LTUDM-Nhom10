# ZIP12: One-time Ed25519 provisioning (Windows CMD and PowerShell)

1. On **Tracker machine only**, in the Git root: `gradlew.bat :tracker-server:generateTicketKeys`. This writes private+public to `%USERPROFILE%\.fileshare-p2p` (or `-PticketKeysDir=...` to choose another directory). It REFUSES to overwrite existing keys. Keep private key out of Git, backups outside version control, and restrict its Windows ACL to the owner.
2. On each trusted Peer computer, copy only `tracker-public.key.b64` into `%USERPROFILE%\.fileshare-p2p\tracker-public.key.b64`. Verify the fingerprint by a separate trusted channel (e.g. physically compare with Tracker output). Do not accept a random downloaded public key as proof of identity.
3. Restart Tracker and ALL Peer clients. The application now finds those files automatically without `set` commands. Existing OS env variables and `.env` paths still override the defaults.
4. For custom locations: `P2P_TICKET_SIGNING_KEY_FILE` (private, Tracker only) and `P2P_TICKET_PUBLIC_KEY_FILE` (public, Peer only). Paths relative to `.env` are resolved from that file's directory.
5. Tracker tickets expire after 900 seconds by default. Set `P2P_TICKET_TTL_SECONDS` on Tracker to a value from 60 through 3600. Invalid values use 900 seconds and emit a warning. Set `P2P_TICKET_KEY_ID` on Tracker to label the active signing key; it defaults to `default`.
6. For key overlap during rotation, each Peer can set `P2P_TICKET_PUBLIC_KEY_FILES` to a semicolon-separated list such as `key-a=C:\keys\tracker-a.pub.b64;key-b=C:\keys\tracker-b.pub.b64`. Keep the old public key until all its tickets have expired. The single-key `P2P_TICKET_PUBLIC_KEY_FILE`/`P2P_TICKET_PUBLIC_KEY_B64` settings remain supported and are associated with `P2P_TICKET_KEY_ID` (default `default`).
7. Insecure localhost-only testing: `P2P_INSECURE_LOOPBACK_DEMO=true` is a special development switch, never use it on a LAN/VPS.
8. Keys are stable: generating a different key pair requires distributing the matching public key again. A mismatch now fails at Tracker startup if the public file is present.
9. Ticket signing is authentication, not encryption. TLS is a separate transport security requirement.

Do not paste PRIVATE key into chat, Sheets, Git or CI logs.
