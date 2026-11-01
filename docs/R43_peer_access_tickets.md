# R43 - signed P2P piece access tickets (PR review required)

Upload peers require a Tracker-issued Ed25519 bearer ticket for GET_BITFIELD,
METADATA_PAGE and REQUEST_PIECE. Tickets are signed only by Tracker and bind
fileId, intended uploading Peer ID, reader Peer ID and time. Each ticket expires
in 60 minutes. Never commit the Tracker private key or secret values.

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

Limitations: bearer tickets can be replayed by anyone who obtains one until
expiration; peer-to-peer TLS is recommended and public deployment is blocked
until TLS identity, token refresh for downloads >60 min, and session revocation
are separately tested. This implementation deliberately does NOT ship keys.

Regression checks: valid, invalid signature, wrong file, wrong intended uploader,
expired, missing, and cross-account source discovery; run an actual 3-peer LAN
transfer in strict mode before accepting R43.
