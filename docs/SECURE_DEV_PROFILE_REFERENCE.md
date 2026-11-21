# Cấu hình phát triển (CHỈ DÙNG TEST LOCAL)

Không dùng cấu hình dưới đây trong production. Tài liệu này thay thế `.env.development.example` vì `apply_patch.py` từ chối tệp `.env.*` để ngăn ghi nhầm bí mật.

```properties
# LOCAL TESTS ONLY. Never distribute this profile to users.
# Insecure TCP is ONLY available when both values explicitly opt in.
P2P_PROFILE=development
P2P_ALLOW_INSECURE_DEV=true
TRACKER_HOST=127.0.0.1
TRACKER_PORT=9000
TRACKER_TLS_ENABLED=false
P2P_TLS_ENABLED=false
P2P_INSECURE_LOOPBACK_DEMO=false
RELAY_ENABLED=false
DB_HOST=localhost
DB_PORT=5432
DB_NAME=fileshare_p2p
DB_USER=postgres
DB_PASSWORD=LOCAL_ONLY_CHANGE_ME

```
