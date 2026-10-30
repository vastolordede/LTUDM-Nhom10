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
├── database/        # schema.sql, migration_v2..v5.sql
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
psql -U postgres -d fileshare_p2p -f database/migration_v3.sql
psql -U postgres -d fileshare_p2p -f database/migration_v4.sql
psql -U postgres -d fileshare_p2p -f database/migration_v5.sql
```

Chạy `schema.sql` rồi `migration_v2.sql` đến `migration_v5.sql` **theo thứ tự** trên DB mới. Trên DB đã có dữ liệu, chỉ chạy migration **chưa được áp dụng**; trước tiên sao lưu dữ liệu.

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

- Có chia sẻ/tải thư mục, selective download và cơ chế NAT probe/relay, nhưng các luồng mở rộng này **chưa có kết quả nghiệm thu trên Internet thực tế**.
- Kết nối trực tiếp qua NAT tùy thuộc cấu hình router/firewall; relay chỉ hoạt động khi cả Tracker và Peer đều bật `RELAY_ENABLED`.
- Chưa kiểm thử thực tế file lớn hơn 2 GB trong bản audit; không đánh dấu PASS khi chỉ nhìn thấy code.
- Chưa xác nhận đầy đủ LAN/JavaFX/PostgreSQL trên mọi môi trường; kiểm tra trên máy Windows trước khi merge.

Chi tiết workflow và testcase: `docs/POST_PATCH_TESTPLAN.md`.

## Bảo mật

- Mật khẩu lưu dạng BCrypt hash.
- Kênh Peer ↔ Tracker: TLS, bật bằng `TRACKER_TLS_ENABLED`.
- Kênh Peer ↔ Peer: TLS, bật bằng `P2P_TLS_ENABLED` (cấu hình qua `P2P_KEYSTORE`, `P2P_TRUSTSTORE`).
- Không commit `.env`, `certs/*.p12`, `certs/*.cer`, `testdata/`.

## Chức năng bổ sung

- Share lại tự động sau khi mở lại Peer (danh sách lưu tại `~/.fileshare-p2p/`).
- Tracker chỉ công bố file khi metadata đủ piece (`publish_status = READY`).
- Một file có nhiều tên (alias), Search được theo mọi tên.
- Tải lại danh sách nguồn từ Tracker khi nguồn rớt giữa chừng (tối đa 3 lần).
- Chọn nguồn theo xếp hạng (độ trễ, tải, piece lỗi), xem bảng `v_peer_ranking`.

## Giả lập nhiều Peer

```bat
.\gradlew.bat :peer-client:emulator -PemuArgs="10 alice demo123 8000 120"
```

Tham số: số peer, username, password, cổng bắt đầu, thời gian chạy (giây).

Để **thực hiện lượt tải thực** thay vì chỉ giả lập Online/Heartbeat, thêm đối số `download`:

```bat
.\gradlew.bat :peer-client:emulator -PemuArgs="5 alice demo123 8000 120 download"
```

Peer phải sử dụng các port khác nhau và cùng kết nối được tới Tracker. Emulator sẽ ghi
`downloads` và `errors`, kiểm tra SHA-256; bài này không thay thế demo GUI ba máy.


## Cập nhật database

Chạy theo thứ tự trên DB đã có `schema.sql`:

```bat
psql -U postgres -d fileshare_p2p -f database/migration_v2.sql
psql -U postgres -d fileshare_p2p -f database/migration_v3.sql
psql -U postgres -d fileshare_p2p -f database/migration_v4.sql
psql -U postgres -d fileshare_p2p -f database/migration_v5.sql
```

## Lưu ý bảo mật và kiểm thử trước deploy

- Khi bật TLS Tracker, certificate phải có SAN khớp `TRACKER_HOST`; hostname được kiểm tra trong handshake.
- `P2P_TLS_VERIFY_HOSTNAME=true` chỉ bật sau khi cấp certificate Peer với SAN khớp địa chỉ kết nối.
- Không deploy ra Internet với `TRACKER_TLS_ENABLED=false` hoặc `P2P_TLS_ENABLED=false` khi truyền dữ liệu nhạy cảm.
- Không mở PostgreSQL cổng 5432 ra Internet.
- Xem `docs/PREDEPLOY_FIXED_TESTS.md` trước khi tạo PR.
