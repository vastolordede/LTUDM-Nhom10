# FileShare-P2P
Phạm Quách Thành Đặng - 3124410070
Nguyễn Thái Thảo - 3124410329
Nguyễn Hoàng Sang - 3124410301

Đồ án **P2P File Sharing**: mô hình lai gồm **Tracker** (quản lý danh mục file, peer online) và các **Peer** truyền file trực tiếp với nhau qua TCP Socket. Tracker chỉ lưu metadata, không lưu nội dung file.

## Stack

- Java 21, Gradle 8.10.2 (wrapper)
- JavaFX 21
- PostgreSQL + JDBC thuần
- Jackson, BCrypt, JUnit 5

## Cấu trúc

```text
FileShare-P2P/
├── common/          # DTO, protocol, TLS helper
├── tracker-server/  # Tracker TCP server
├── peer-client/     # JavaFX GUI, upload server, downloader
├── database/        # schema.sql, migration_v2..v9.sql, verify_schema.sql
└── docs/
```

## Tính năng đã có

- Đăng nhập/đăng xuất với mật khẩu lưu dạng BCrypt; heartbeat và quản lý trạng thái online/offline.
- Chia sẻ file và thư mục; một peer chỉ có một bản chia sẻ cho mỗi hash. Tracker chỉ công bố file khi metadata đã sẵn sàng.
- Tìm kiếm file và thư mục; chi tiết thư mục không hiển thị file peer đã unshare.
- Tải file đa nguồn, resume, retry nguồn, Rarest-First và upload các piece đã tải xong; kiểm tra SHA-256 của piece/toàn file theo trusted hash.
- Lịch sử truyền, thống kê Tracker, tốc độ truyền và chẩn đoán trạng thái kết nối LAN / Internet Direct / Relay.
- Kiểm tra tên file, quyền ghi và dung lượng đích trước khi tải.

SHA-256 ở đây dùng để kiểm tra tính toàn vẹn, không mã hóa file. Ed25519 ký ticket truy cập, không ký hay mã hóa nội dung file. TLS bảo vệ dữ liệu trên đường truyền khi được bật/cấu hình.

## Chuẩn bị PostgreSQL

```bat
psql -v ON_ERROR_STOP=1 -U postgres -c "CREATE DATABASE fileshare_p2p"
psql -v ON_ERROR_STOP=1 -U postgres -d fileshare_p2p -f database/schema.sql
psql -v ON_ERROR_STOP=1 -U postgres -d fileshare_p2p -f database/migration_v2.sql
psql -v ON_ERROR_STOP=1 -U postgres -d fileshare_p2p -f database/migration_v3.sql
psql -v ON_ERROR_STOP=1 -U postgres -d fileshare_p2p -f database/migration_v4.sql
psql -v ON_ERROR_STOP=1 -U postgres -d fileshare_p2p -f database/migration_v5.sql
psql -v ON_ERROR_STOP=1 -U postgres -d fileshare_p2p -f database/migration_v6.sql
psql -v ON_ERROR_STOP=1 -U postgres -d fileshare_p2p -f database/migration_v7.sql
psql -v ON_ERROR_STOP=1 -U postgres -d fileshare_p2p -f database/migration_v8.sql
psql -v ON_ERROR_STOP=1 -U postgres -d fileshare_p2p -f database/migration_v9.sql
psql -v ON_ERROR_STOP=1 -U postgres -d fileshare_p2p -f database/verify_schema.sql
```

Chỉ dùng chuỗi đầy đủ trên cho database mới, rỗng. Chạy đúng thứ tự; dừng khi có lỗi. `verify_schema.sql` kiểm tra schema và dữ liệu rồi ghi `schema_version = 9`; Tracker từ chối khởi động nếu schema chưa được verify. Với DB đã có dữ liệu, sao lưu trước, kiểm tra migration đã áp dụng và chỉ chạy các migration còn thiếu theo thứ tự. Một số migration không an toàn để chạy lại; checksum manifest chỉ kiểm tra nội dung file SQL, không chứng minh migration đã chạy trong DB. Xem [hướng dẫn PostgreSQL, backup và khôi phục](docs/database_bootstrap_v6.md).

## Cấu hình

Tracker đọc cấu hình theo thứ tự: biến môi trường hệ điều hành > `.env` > `application.properties` > mặc định. `TrackerSettings` tạo `DatabaseConfig`; `DB_PASSWORD` bắt buộc. Peer có cấu hình kết nối riêng từ cùng các nguồn. Không commit `.env` hoặc secret.

## Chạy trên Windows / JDK 21

Tạo tài khoản thử bằng credential dùng riêng cho môi trường phát triển (không dùng lại mật khẩu thật):

```bat
.\gradlew.bat :tracker-server:createUser --args="<username> <test-password>"
```

- Terminal A (Tracker): `.\gradlew.bat :tracker-server:run`
- Terminal B (GUI): `.\gradlew.bat :peer-client:run`
- Cổng mặc định: Tracker `9000`, Rendezvous/Relay `9001`, peer lắng nghe `7001`; có thể cấu hình lại.
- Chạy test và build: `.\gradlew.bat clean test build`

Giao thức control dùng `MessageEnvelope` phiên bản 3, 4-byte length prefix và JSON UTF-8 tối đa 1 MiB; nội dung piece truyền riêng dạng binary. Để thử đa nguồn, chạy nhiều peer với cổng lắng nghe khác nhau và cùng kết nối được tới Tracker.

## Quy ước chung

- Tracker chỉ truyền control/metadata, không truyền nội dung file.
- Tin nhắn điều khiển dùng `MessageEnvelope` + `ProtocolCodec` (4-byte length + JSON UTF-8, tối đa 1 MiB); dữ liệu piece truyền riêng dạng binary.
- IP của Peer lấy từ socket phía Tracker; client chỉ công bố `listeningPort`.
- Password không lưu plaintext (BCrypt). SHA-256 là kiểm tra integrity; Ed25519 là chữ ký ticket, không phải mã hóa file.
- TLS bảo vệ vận chuyển: Peer ↔ Tracker cấu hình bằng `TRACKER_TLS_ENABLED`, Peer ↔ Peer bằng `P2P_TLS_ENABLED`. TLS không thay thế xác minh fingerprint public key Tracker.

## Giới hạn đã biết và nghiệm thu

- Màn hình Share chưa có nút dừng chia sẻ thư mục; service có thao tác unshare, nhưng chưa có nút GUI tương ứng.
- NAT/Relay qua Internet thực, các kết nối trên nhiều mạng và app-image chạy trên máy Windows sạch chưa được nghiệm thu. Chẩn đoán trạng thái trong GUI không thay thế nghiệm thu mạng/máy thật.
- PostgreSQL recovery integration test mặc định **skip** khi không có `P2P_PG_TEST_URL`. Chạy trên Windows có Docker bằng `.\tracker-server\src\test\run_postgres_recovery_acceptance.ps1`.
- Chưa có nghiệm thu máy thật cho kích thước file lớn hơn 2 GB. Không suy diễn nghiệm thu từ việc code hoặc unit test tồn tại.
- `tools/postgres_backup_restore.ps1` là script PowerShell cho Windows. Không có down migration; rollback là khôi phục backup tương thích vào DB riêng rồi chạy bản Tracker phù hợp.

Các tình huống LAN/Internet, Relay, app-image và khôi phục trên môi trường triển khai cần được ghi nhận nghiệm thu riêng trước khi tuyên bố đã chạy thành công.

## Bảo mật và quản lý secret

| Dữ liệu | Ai giữ / ở đâu | GUI Peer có lưu không? |
|---|---|---|
| PostgreSQL password (`DB_PASSWORD`) | Quản trị viên/DBA; secret store hoặc môi trường được bảo vệ của Tracker | Không |
| Tracker TLS keystore password (`TRACKER_TLS_KEYSTORE_PASSWORD`) | Quản trị viên Tracker; secret store hoặc môi trường được bảo vệ của Tracker | Không |
| Peer TLS truststore password (`TRACKER_TRUSTSTORE_PASSWORD`) | Quản trị viên; môi trường hệ điều hành được bảo vệ | Không; GUI chỉ lưu đường dẫn truststore |
| Ed25519 ticket signing private key (`P2P_TICKET_SIGNING_KEY_FILE`) | Chỉ trên máy Tracker, giới hạn quyền truy cập | Không |
| Tracker public key và fingerprint | Quản trị viên phân phối qua kênh tin cậy độc lập | Public key tin cậy được lưu cục bộ; không phải secret |
| Mật khẩu tài khoản Peer | Người dùng nhập khi đăng nhập | Không lưu trong cấu hình Peer |

Sau thay đổi định dạng/ghim public key, máy đang dùng public key kiểu cũ phải import lại public key và xác nhận fingerprint SHA-256 dạng hex nhóm bằng một kênh tin cậy độc lập. Không chấp nhận fingerprint chỉ lấy từ cùng nguồn với file khóa. Không đưa password, private key, truststore chứa secret hoặc `.env` vào Git hay app-image.

## Giả lập nhiều Peer

```bat
.\gradlew.bat :peer-client:emulator -PemuArgs="<count> <username> <test-password> <start-port> <seconds>"
```

Thay placeholder bằng thông tin của môi trường phát triển. Tham số lần lượt là số peer, username, password thử nghiệm, cổng bắt đầu và thời gian chạy (giây).

Để chạy workload tải thực thay vì chỉ giả lập Online/Heartbeat, thêm đối số `download`:

```bat
.\gradlew.bat :peer-client:emulator -PemuArgs="<count> <username> <test-password> <start-port> <seconds> download"
```

Peer phải dùng port khác nhau và cùng kết nối được tới Tracker. Emulator không thay thế nghiệm thu GUI nhiều máy.

## Cách chạy test và kiểm tra trước khi gửi thay đổi

Từ thư mục gốc, chạy bốn script kiểm tra và build/test:

```bat
python tools/check_repo_markers.py
python tools/check_db_migrations.py
python tools/check_fxml_bindings.py
python tools/check_release_hygiene.py
.\gradlew.bat clean test build
```

Các lệnh trên kiểm tra marker trong repository, migration, liên kết FXML và release hygiene, sau đó chạy Gradle test/build. PostgreSQL recovery integration test nêu ở phần giới hạn cần chạy riêng bằng script Docker trên Windows; test mặc định có thể skip nếu thiếu cấu hình PostgreSQL.

## Lưu ý bảo mật và kiểm thử trước deploy

- Khi bật TLS Tracker, certificate phải có SAN khớp `TRACKER_HOST`; hostname được kiểm tra trong handshake.
- `P2P_TLS_VERIFY_HOSTNAME=true` chỉ bật sau khi cấp certificate Peer với SAN khớp địa chỉ kết nối.
- Không deploy ra Internet với `TRACKER_TLS_ENABLED=false` hoặc `P2P_TLS_ENABLED=false` khi truyền dữ liệu nhạy cảm.
- Không mở PostgreSQL cổng 5432 ra Internet.
- Với cấu hình Internet, bật TLS cho Tracker và Peer, xác minh certificate name/SAN, và không bỏ qua ticket hay fingerprint.
- Tạo khóa ticket một lần trên máy Tracker bằng `.\gradlew.bat :tracker-server:generateTicketKeys`. Chỉ phân phối public key; gửi fingerprint grouped-hex qua kênh độc lập và tin cậy.
- PostgreSQL backup/restore và app-image là quy trình vận hành riêng; xem hướng dẫn migration và checklist nghiệm thu trước khi triển khai.
