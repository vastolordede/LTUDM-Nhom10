# R18/R19/R61 security, logging, privacy: review checklist

The Tracker must authenticate session UUIDs before exposing catalog, source,
folder, transfer history and share operations. PeerUploadServer now verifies
Tracker-signed file/uploader tickets (R43). This establishes **authorization
for a bearer token**, not client identity over the P2P transport by itself;
use trusted TLS and guard tokens against interception. An attacker possessing
an unexpired bearer ticket may replay it during its lifetime. Use only
short-lived tickets and rotate keys before public exposure.

Data intentionally discoverable by a logged-in Peer: public file names, file
sizes, SHA-256 content hashes, availability, source Peer IP/listening port,
and folder names. Do not advertise secret filenames, personal directories or
private network services. Stop Sharing removes source availability but does
not necessarily erase existing aliases/history from the Tracker DB.

Local secret precautions:
- Keep `P2P_TICKET_SIGNING_KEY_B64` only on Tracker; distribute PUBLIC key only
  to peers. Never commit either key's private file or token into `.env` in Git.
- GitHub CI runs `tools/check_release_hygiene.py` for tracked PEM, P12, JKS,
  `.env`, long-form private keys and known token formats; this is not a full
  secret scanner and cannot retroactively erase Git history.
- Inspect logs for credentials/session IDs/personal paths before sharing logs.
  Tracker ProtocolCodec now avoids printing malformed authentication payloads.
- Use database least privilege, backups and restricted operator access.

Unresolved before Internet: threat model for untrusted relays, permission to
list source IPs, owner control over indexed filenames, metadata lifetime,
audit-retention policy, P2P TLS identity validation and ticket replay risk.
