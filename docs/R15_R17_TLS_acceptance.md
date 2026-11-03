# R15-R17 / R44 / R73: TLS acceptance

## Automated coverage

Tests create ephemeral PKCS12 keys and truststores with `keytool` under the JUnit
temporary directory; no certificate, private key, or keystore is committed.

| Channel | Automated cases |
|---|---|
| Peer-Tracker | Trusted certificate with matching IP SAN succeeds; unrelated truststore, expired certificate, and mismatched hostname fail the TLS handshake; connection closed after failure; server closing after ClientHello fails within the configured timeout. |
| Peer-Peer | Trusted certificate with matching IP SAN succeeds; unrelated truststore, expired certificate, and mismatched hostname fail the TLS handshake. |
| TLS/plaintext and recovery | Existing Tracker mismatch and reconnect tests cover TCP/TLS mismatch and using corrected Tracker settings without restarting. |
| Relay fallback | TLS handshake, certificate, and hostname failures are terminal; connection-refused transport failures remain eligible for Relay. |

These are automated local-endpoint tests, not a substitute for staging/production
acceptance on real machines.

## Manual acceptance — not run

The following checks require configured real Tracker/Peer hosts and have **not
been run on real machines**:

1. Tracker TLS with the production truststore: login, heartbeat, and catalog operations.
2. Replace the Tracker truststore with an unrelated one; verify fail-closed behavior and UI guidance.
3. Use an expired Tracker certificate on a staging VPS; verify rejection.
4. Connect to Tracker using a hostname/IP absent from the certificate SAN; verify hostname rejection.
5. Configure a Peer truststore with the wrong CA and a peer endpoint with a mismatched SAN; verify fail-closed behavior.
6. Confirm TLS failures do not route through Relay, while a pure network outage still permits Relay.
7. Change Tracker settings while logged out and reconnect without restarting the application.
8. Test multiple Peers on Windows and Linux; confirm logs contain no secrets.

Never commit private keys, certificate passwords, P12/JKS files, or `.env` files.
