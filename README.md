# FileShare-P2P
Phạm Quách Thành Đặng - 3124410070
Nguyễn Thái Thảo - 3124410329
Nguyễn Hoàng Sang - 3124410301
đồ án **P2P File Sharing** .

- Khởi tạo Gradle multi-module.
- Chốt cấu trúc `common`, `tracker-server`, `peer-client`.
- PostgreSQL schema ban đầu cho `users`, `peer_sessions`.
- Chốt protocol v1 cho:
  - `LOGIN_REQUEST / LOGIN_RESPONSE`
  - `LOGOUT_REQUEST / LOGOUT_RESPONSE`
  - `HEARTBEAT / HEARTBEAT_ACK`
- Chốt framing: **4-byte length + JSON UTF-8**.
- DTO/model dùng chung.
- Skeleton Tracker configuration/repository.
- JavaFX Login UI skeleton.
- Login flow / Logout flow / Heartbeat flow.
- Unit test cho protocol codec.

## Stack

- Java 21
- Gradle
- JavaFX 21
- PostgreSQL + JDBC
- Jackson
- BCrypt (đã khai báo dependency để dùng từ Week 2)
- JUnit 5

## Cấu trúc

```text
FileShare-P2P/
├── common/
├── tracker-server/
├── peer-client/
├── database/
└── docs/
```

## Chuẩn bị PostgreSQL

Tạo database:

```sql
CREATE DATABASE fileshare_p2p;
```

Sau đó chạy:

```text
database/schema.sql
```

Copy:

```text
tracker-server/src/main/resources/application.example.properties
```

thành:

```text
tracker-server/src/main/resources/application.properties
```

rồi sửa username/password PostgreSQL trên máy cá nhân.

`application.properties` đã nằm trong `.gitignore`, không commit mật khẩu thật lên Git.

## Chạy trong IntelliJ IDEA

1. Mở thư mục gốc `FileShare-P2P`.
2. Chọn **Trust Project** nếu IntelliJ hỏi.
3. Chờ Gradle Sync tải dependencies.
4. Chọn JDK 21 cho Gradle JVM.
5. Chạy:
   - `vn.edu.p2p.peer.PeerApplication` để xem Login UI.
   - `vn.edu.p2p.tracker.TrackerApplication` để kiểm tra Tracker Week 1 config.

Hoặc dùng Gradle:

```bash
gradle :peer-client:run
gradle :tracker-server:run
gradle test
```

## Quy ước chung cần giữ

- `Tracker` chỉ truyền control/metadata; **không truyền nội dung file**.
- Peer ↔ Tracker và Peer ↔ Peer đều dùng TCP; TLS sẽ được tích hợp trước khi truyền credential/data thật.
- IP của Peer về sau nên lấy từ socket phía Tracker; client chỉ công bố `listeningPort`.
- Password **không lưu plaintext**. Week 2 dùng BCrypt.
- SHA-256 dành cho file integrity, không dùng thay password hashing.
- Tất cả message điều khiển dùng `MessageEnvelope` và `ProtocolCodec` trong module `common`.


## Update cho repo LTUDM-Nhom10 (D52-D68)

Repo GitHub duoc doi ten thanh `LTUDM-Nhom10`. Ten folder local va ten Gradle project
`FileShare-P2P` co the giu nguyen, khong anh huong giao thuc P2P.

### Chay thu tren Windows 10 / JDK 21

- Tao PostgreSQL database `fileshare_p2p`; chay `database/schema.sql` **mot lan tren DB moi**.
- Copy `.env.example` thanh `.env`; nhap mat khau DB o file `.env` local (khong commit).
- Tao tai khoan thu qua terminal (nhap dung username/password test):

```bat
.\gradlew.bat :tracker-server:createUser --args="alice demo123"
```

- Terminal A chay Tracker: `.\gradlew.bat :tracker-server:run`
- Terminal B chay GUI: `.\gradlew.bat :peer-client:run`
- Login -> Peer Center, dung tab Share / My Files / Search / Download / History.
- De thu da nguon, can 3 Peer co peer ID rieng va cung ket noi duoc toi Tracker + port upload.

### Ky thuat

- Tracker luu metadata va ket noi nguon; khong luu noi dung binary cua file.
- SHA-256 duoc kiem tra toan file va tung piece; co ho tro resume.
- Khi file lon, metadata duoc gui theo batch, khong doi `ProtocolCodec.MAX_JSON_BYTES`.
- Piece size tu dong thay doi (256 KiB, 1 MiB, 4 MiB, 16 MiB) theo do lon file.
- Chia se thu muc va tai nguyen thu muc (D64-D65) **chua duoc implement**.
- Chua test duoc full LAN/JavaFX/PostgreSQL tren moi truong tao patch; xac nhan tren may Windows truoc khi merge PR.

Chi tiet workflow va testcase: `docs/POST_PATCH_TESTPLAN.md`.
