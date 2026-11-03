# R43 - signed P2P piece access tickets (PR review required)

Upload peers require a Tracker-issued Ed25519 ticket for GET_BITFIELD,
METADATA_PAGE and REQUEST_PIECE. Bitfield and piece requests also require a
requester proof-of-possession. Tickets bind fileId, intended uploading Peer ID,
reader Peer ID, time, requester public key, and signing key ID. TTL defaults to
15 minutes and is configurable from 60 to 3600 seconds. Never commit the Tracker
private key or secret values.

## Configure on local development machines

Generate Ed25519 keys with OpenSSL (the binary private file is SECRET):

```
openssl genpkey -algorithm Ed25519 -outform DER -out tracker-ticket-private.der
openssl pkey -inform DER -in tracker-ticket-private.der -pubout -outform DER -out ticket-public.der
```

Use PowerShell to base64 encode each file:

```
[Convert]::ToBase64String([IO.File]::ReadAllBytes('tracker-ticket-private.der'))
[Convert]::ToBase64String([IO.File]::ReadAllBytes('ticket-public.der'))
```

- On Tracker ONLY: `P2P_TICKET_SIGNING_KEY_B64=<private-key-base64>`.
- On EVERY Peer: `P2P_TICKET_PUBLIC_KEY_B64=<public-key-base64>`.
- For an isolated loopback demo, set `P2P_INSECURE_LOOPBACK_DEMO=true`
  on Tracker and every Peer, and do not distribute the demo outside a single
  workstation. In this mode only loopback requests can omit tickets and
  signing is disabled on Tracker.

These env variables can be supplied through your existing local `.env` file,
which must not be committed. In normal mode, Tracker fails startup if its
signing key is absent; upload peers deny requests if their public key is absent.

Configure `P2P_TICKET_TTL_SECONDS` on Tracker to change the lifetime; invalid
values fall back to 900 seconds with a warning. A downloader refreshes tickets
through Tracker before a P2P request when 60 seconds or less remain.
`P2P_TICKET_KEY_ID` labels the active Tracker key (default `default`). Peers can
trust multiple public keys through `P2P_TICKET_PUBLIC_KEY_FILES` entries in
`keyId=path;keyId=path` form. During rotation, distribute the new public key
alongside the old one before switching Tracker, and retain the old public key
for at least the maximum ticket lifetime.

Logout, unshare, and session expiry revocations propagate over heartbeat
(near-immediate, up to the heartbeat interval plus network delay). The Tracker
revocation registry is in memory; after a Tracker restart, outstanding tickets
remain bounded by their expiry. This implementation deliberately does NOT ship
keys.

Regression checks: valid, invalid signature, wrong file, wrong intended uploader,
expired, missing, and cross-account source discovery; run an actual 3-peer LAN
transfer in strict mode before accepting R43.
