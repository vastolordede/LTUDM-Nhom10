# Mẫu tham khảo cấu hình triển khai production (dành cho quản trị viên)

Không ghi đè `.env.example` trong repository vì không được thu thập từ nhánh hiện tại.
Các giá trị bên dưới là bản mẫu, **không có mật khẩu/chứng chỉ thật**.
Chỉ sử dụng sau khi cấu hình CA, Tracker, Peer và cấp truststore đúng môi trường.

```dotenv
# FileShare-P2P production deployment template (admin only)
# Copy this file to .env and fill secrets locally.
# Never commit .env.

# =========================
# Database
# =========================
DB_HOST=localhost
DB_PORT=5432
DB_NAME=fileshare_p2p
DB_USER=postgres
DB_PASSWORD=CHANGE_ME

# =========================
# Tracker / Peer connection
# =========================
# Use a stable hostname present in the Tracker TLS certificate SAN.
TRACKER_HOST=tracker.example.com
TRACKER_PORT=9000
# Production is the default; dev TCP needs a separate explicit local-test opt-in.
# Secure client/server policy; no user-facing encryption toggle.
P2P_PROFILE=production
# Insecure developer testing needs a deliberate separate opt-in; never ship it.
P2P_ALLOW_INSECURE_DEV=false

# =========================
# Heartbeat / Statistics
# =========================
HEARTBEAT_INTERVAL_SECONDS=10
HEARTBEAT_TIMEOUT_SECONDS=30
STATISTICS_INTERVAL_SECONDS=30

# =========================
# Tracker concurrency / timeout
# =========================
TRACKER_WORKER_THREADS=16
TRACKER_WORKER_QUEUE_CAPACITY=128
TRACKER_SOCKET_READ_TIMEOUT_MILLIS=30000
TRACKER_LOG_LEVEL=INFO

# =========================
# Tracker TLS server
# =========================
TRACKER_TLS_ENABLED=true
TRACKER_TLS_KEYSTORE=certs/tracker-server.p12
TRACKER_TLS_KEYSTORE_PASSWORD=CHANGE_ME
TRACKER_TLS_KEYSTORE_TYPE=PKCS12

# =========================
# Peer truststore for Tracker
# =========================
TRACKER_TRUSTSTORE=certs/peer-truststore.p12
TRACKER_TRUSTSTORE_PASSWORD=CHANGE_ME
TRACKER_TRUSTSTORE_TYPE=PKCS12

# =========================
# Peer-to-Peer TLS
# =========================
P2P_TLS_ENABLED=true
P2P_KEYSTORE=certs/peer-p2p.p12
P2P_KEYSTORE_PASSWORD=CHANGE_ME
P2P_TRUSTSTORE=certs/peer-p2p-truststore.p12
P2P_TRUSTSTORE_PASSWORD=CHANGE_ME

# =========================
# Internet rendezvous / relay (optional)
# =========================
RENDEZVOUS_PORT=9001
# Relay requires an enabled/publicly reachable rendezvous service; admin only.
# With true on BOTH sides, the Peer tries Relay automatically if direct fails.
RELAY_ENABLED=true
# Every peer needs its own CA-signed certificate with SAN matching the advertised DNS/IP.
P2P_TLS_VERIFY_HOSTNAME=true

# =========================
# Tracker-signed P2P ticket keys (configure once)
# =========================
# Default: ~/.fileshare-p2p/tracker-private.key.b64 on Tracker only,
# and ~/.fileshare-p2p/tracker-public.key.b64 on Peer clients.
# Generate on Tracker: gradlew.bat :tracker-server:generateTicketKeys
# On LAN copy ONLY the public file to each trusted Peer.
# P2P_TICKET_SIGNING_KEY_FILE=certs/tracker-private.key.b64
# P2P_TICKET_PUBLIC_KEY_FILE=certs/tracker-public.key.b64
P2P_INSECURE_LOOPBACK_DEMO=false
```
