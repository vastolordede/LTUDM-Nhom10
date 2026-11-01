# R15-R17 / R44: TLS acceptance plan (manual, not completed)

The application includes TLS socket provider factories for Peer-Tracker and
Peer-Peer. Set a trusted Tracker DNS certificate and Peer truststore, plus
P2P TLS key/truststores, using the *existing project configuration*.

Test cases to record with date, hostnames, certificate fingerprints and logs:
1. Tracker TLS truststore valid -> login, heartbeat, catalog operations succeed.
2. Wrong truststore -> handshake must fail closed with actionable UI message.
3. Certificate expired -> deny connection; record TLS exception and client clock.
4. Wrong DNS/SAN -> deny hostname verification (do not disable verification).
5. Peer TLS CA mismatch -> downloader cannot request pieces; fail closed.
6. Plain TCP client to TLS server and TLS client to TCP server -> fail quickly.
7. Change settings while logged out -> retry connection without restarting app.
8. Register/Load test 3+ Peers across Windows + Linux; no secret in debug logs.

Never commit private key, certificate password, P12/JKS or `.env`. No certs
were generated or validated in this offline patch set.
