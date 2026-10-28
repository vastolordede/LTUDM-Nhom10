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
├── database/        # schema.sql, migration_v2.sql
└── docs/
```

## Chức năng hiện có

- Login/Logout (BCrypt), Heartbeat, Online/Offline, reconnect không tạo session trùng.
- Share file: SHA-256 toàn file, piece size thích ứng (256 KiB / 1 / 4 / 16 MiB), metadata phân trang cho file lớn.
- Search theo tên, hiển thị số nguồn online.
- Download đa nguồn nhiều luồng, kiểm tra SHA-256 từng piece và toàn file, Resume, retry sang nguồn khác, blacklist peer gửi piece lỗi, Rarest-First.
- Vừa tải vừa upload (chỉ upload piece đã hoàn thành).
- Transfer History, thống kê Tracker, hiển thị tốc độ.
- Kiểm tra trước khi tải: tên file hợp lệ trên Windows, thư mục ghi được, đủ dung lượng trống.

## Chuẩn bị PostgreSQL

```bat
psql -U postgres -c "CREATE DATABASE fileshare_p2p"
psql -U postgres -d fileshare_p2p -f database/schema.sql
psql -U postgres -d fileshare_p2p -f database/migration_v2.sql
```

Chạy `schema.sql` và `migration_v2.sql` **một lần trên DB mới**.

## Cấu hình

Copy `.env.example` thành `.env`, nhập mật khẩu DB (`DB_PASSWORD` bắt buộc). Không commit `.env`.
Thứ tự ưu tiên: biến môi trường OS > `.env` > `application.properties` > giá trị mặc định.

## Chạy trên Windows / JDK 21

Tạo tài khoản thử:

```bat
.\gradlew.bat :tracker-server:createUser --args="alice demo123"
```

- Terminal A (Tracker): `.\gradlew.bat :tracker-server:run`
- Terminal B (GUI): `.\gradlew.bat :peer-client:run`
- Chạy test: `.\gradlew.bat clean test`

Login → Peer Center, dùng các tab Share / My Files / Search / Download / History.
Để thử đa nguồn cần 3 Peer, mỗi Peer có peer ID riêng và cổng lắng nghe riêng (mặc định 7001), cùng kết nối được tới Tracker.

## Quy ước chung

- Tracker chỉ truyền control/metadata, không truyền nội dung file.
- Tin nhắn điều khiển dùng `MessageEnvelope` + `ProtocolCodec` (4-byte length + JSON UTF-8, tối đa 1 MiB); dữ liệu piece truyền riêng dạng binary.
- IP của Peer lấy từ socket phía Tracker; client chỉ công bố `listeningPort`.
- Password không lưu plaintext (BCrypt). SHA-256 dùng cho toàn vẹn file.
- Kênh Peer ↔ Tracker hỗ trợ TLS (bật bằng cấu hình, mặc định tắt). Trạng thái TLS kênh Peer ↔ Peer xem `docs/POST_PATCH_TESTPLAN.md`.

## Giới hạn đã biết

- Chưa hỗ trợ chia sẻ/tải thư mục (D64-D65).
- Không có NAT traversal: truyền file giữa các Peer demo trong cùng mạng LAN; chỉ Tracker cần triển khai trên Internet.
- Chưa kiểm thử thực tế file lớn hơn 2 GB.
- Chưa xác nhận đầy đủ LAN/JavaFX/PostgreSQL trên mọi môi trường; kiểm tra trên máy Windows trước khi merge.

Chi tiết workflow và testcase: `docs/POST_PATCH_TESTPLAN.md`.

## Bảo mật

- Mật khẩu lưu dạng BCrypt hash.
- Kênh Peer ↔ Tracker: TLS, bật bằng `TRACKER_TLS_ENABLED`.
- Kênh Peer ↔ Peer: TLS, bật bằng `P2P_TLS_ENABLED` (keystore/truststore cấu hình qua `P2P_KEYSTORE`, `P2P_TRUSTSTORE`).
- Không commit `.env`, `certs/*.p12`, `certs/*.cer`.

## Giả lập nhiều Peer (kiểm thử tải)

```bat
.\gradlew.bat :peer-client:emulator -PemuArgs="10 alice demo123 8000 120"
```

Tham số: số peer, username, password, cổng bắt đầu, thời gian chạy (giây). Mỗi virtual peer có ID riêng, tự share file, heartbeat và thỉnh thoảng logout/login lại.