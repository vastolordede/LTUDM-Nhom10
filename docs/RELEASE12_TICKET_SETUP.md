# ZIP12: One-time Ed25519 provisioning (Windows CMD and PowerShell)

1. On **Tracker machine only**, in the Git root: `gradlew.bat :tracker-server:generateTicketKeys`. This writes private+public to `%USERPROFILE%\.fileshare-p2p` (or `-PticketKeysDir=...` to choose another directory). It REFUSES to overwrite existing keys. Keep private key out of Git, backups outside version control, and restrict its Windows ACL to the owner.
2. On each trusted Peer computer, copy only `tracker-public.key.b64` into `%USERPROFILE%\.fileshare-p2p\tracker-public.key.b64`. Verify the fingerprint by a separate trusted channel (e.g. physically compare with Tracker output). Do not accept a random downloaded public key as proof of identity.
3. Restart Tracker and ALL Peer clients. The application now finds those files automatically without `set` commands. Existing OS env variables and `.env` paths still override the defaults.
4. For custom locations: `P2P_TICKET_SIGNING_KEY_FILE` (private, Tracker only) and `P2P_TICKET_PUBLIC_KEY_FILE` (public, Peer only). Paths relative to `.env` are resolved from that file's directory.
5. Insecure localhost-only testing: `P2P_INSECURE_LOOPBACK_DEMO=true` is a special development switch, never use it on a LAN/VPS.
6. Keys are stable: generating a different key pair requires distributing the matching public key again. A mismatch now fails at Tracker startup if the public file is present.
7. Ticket signing is authentication, not encryption. TLS is a separate transport security requirement.

Do not paste PRIVATE key into chat, Sheets, Git or CI logs.
