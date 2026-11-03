# ZIP18: VPS/staging infrastructure and final acceptance (NO LIVE VPS DEPLOYMENT)

## Staging preparation

- Use a dedicated Ubuntu VPS, public IP, minimum plausible 2 GiB RAM for small Java+PostgreSQL staging (validate real usage), no public PostgreSQL port 5432.
- Install Docker Engine and Compose v2, restrict SSH keys, configure host and cloud firewall (Docker published ports can bypass naive UFW assumptions).
- Build Docker image from repo root with `docker build -f infra/staging/Tracker.Dockerfile -t p2p-tracker:review .`; verify image **does not contain `.env`, private keys or DB files**. This Dockerfile now uses explicit COPY paths.
- Place TLS keystore, Ed25519 PRIVATE key and DB secret in operator-controlled private paths outside Git; copy only public key/certificate trust anchors to Peers after fingerprint verification.
- **PRODUCTION profile** requires Tracker TLS and Peer P2P TLS/hostname verification; do not expose insecure plaintext LAN demo over Internet.
- DB: take backup with pg_dump; restore to disposable database and migrate only missing v2..v9 scripts; run verify_schema; no automatic destructive migration from these ZIPs.
- After `docker compose ... up -d` verify logs, public TLS handshake with hostname, register/login, search, download, SHA-256 and restricted DB reachability. TCP healthcheck is only listener liveness, **not end-to-end readiness**.
- Pin a release image by immutable digest; backup first; roll back image tag after failed rollout. A rollback involving a DB schema change requires a rehearsed backward compatibility plan, NOT just `docker compose pull`.

## Known remaining gaps at ZIP18

- Ed25519 bearer tickets are not proof-of-possession bound to requester identity and are not instantaneously revocable after unshare/logout. Do not claim full resistance to token theft/replay on hostile networks.
- Protocol change in ZIP14 requires simultaneous client/server upgrade; no compatibility negotiation was added.
- Protocol 3 requires Tracker and Peers to upgrade together. Apply database migration v9; Peers register their Ed25519 public key during authenticated login.
- No Docker/Gradle/Postgres/live LAN/VPS tests were executed during off-line ZIP generation. The evidence matrix intentionally has NOT_RUN status.
- 17 school-specific requirements must be compared with original specification; `R58_17_REQUIREMENTS_EVIDENCE.csv` in the snapshot contains placeholders.
- Windows app-image needs validation on a clean physical/VM Windows installation before release.
