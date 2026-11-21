# FileShare-P2P: Secure-by-default release (Windows 10 + Java 21)

## Mục tiêu và giới hạn

- Người dùng cuối **không bật/tắt TLS**: bản phát hành dùng `P2P_PROFILE=production` và TLS bắt buộc cho Tracker và Peer-to-Peer; TLS thất bại -> từ chối kết nối, **không quay về TCP thường**.
- Kết nối được xác thực: TLS xác minh SAN/hostname và chuỗi chứng chỉ của CA tin cậy; ticket Ed25519 và chữ ký yêu cầu vẫn xác minh ở tầng ứng dụng; SHA-256 kiểm tra mảnh/file.
- **Nhà phát hành / quản trị viên** phải cấp phát CA, chứng chỉ riêng cho từng máy Peer, DNS/IP ổn định, công bố ticket public key theo đường tin cậy và thiết lập Tracker/PostgreSQL/Relay. Đây là bước phát hành **trước khi đưa ứng dụng cho người dùng**; chưa có dịch vụ tự cấp chứng chỉ từ xa cho máy mới có IP động.
- User story “chỉ đăng ký rồi dùng” chỉ đạt trên **bản cài đặt đã được cấp cấu hình và kiểm thử**, KHÔNG đạt chỉ bằng việc sao chép repository ZIP lên máy sạch.

## Bước quản trị (thực hiện một lần cho mỗi bản triển khai)

Cần JDK 21 (`java`, `keytool`) và máy quản trị bảo mật.

```powershell
java scripts/SecureReleaseProvisioner.java D:\P2P-Secure-Release tracker.example.local peer001=192.168.1.101 peer002=192.168.1.102
```

Lệnh tạo các thư mục:

- `ADMIN_KEEP_PRIVATE/`: CA private key + mật khẩu; **giữ offline**, không phân phối, không đẩy Git.
- `TRACKER_INSTALL/`: Tracker keystore, private ticket signing key, cấu hình `.env`; chỉ sử dụng trên Tracker.
- `PEERS/peer001/`, `PEERS/peer002/`: **mỗi máy một khóa riêng**, hai truststore CA, ticket public key và `.env` tương ứng.

**Cảnh báo:** không đẩy bất kỳ thư mục đầu ra nào lên GitHub. `TRACKER_HOST` và địa chỉ Peer phải khớp DNS/IP thực tế dùng kết nối và SAN trong chứng chỉ. Peer di động, thay IP, dùng NAT/cổng công khai động có thể cần cấp lại chứng chỉ hoặc tên miền ổn định; chưa có enrollment/rotation tự động.

Trên Tracker, đặt `P2P_ENV_FILE` trỏ vào `TRACKER_INSTALL/.env` **và cung cấp DB_PASSWORD qua biến môi trường bí mật**. Tracker cần PostgreSQL và các cổng TLS 9000, rendezvous TLS 9001 nếu triển khai Relay. **Không chia sẻ** `TRACKER_INSTALL/certs/tracker-private.key.b64` hoặc `ADMIN_KEEP_PRIVATE` với Peer.

Trên mỗi máy người dùng, quản trị viên giao **đúng gói Peer riêng của máy**, rồi cài đặt:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install_secure_peer_windows.ps1 -PeerProfileDirectory D:\P2P-Secure-Release\PEERS\peer001
```

Trình cài đặt lưu hồ sơ ở `%USERPROFILE%\.fileshare-p2p` và hạn chế ACL bằng `icacls`. Người dùng khởi động ứng dụng đã build qua `scripts/run_secure_peer_windows.cmd` hoặc launcher tương đương trỏ `P2P_ENV_FILE` vào hồ sơ đó, rồi tạo tài khoản và đăng nhập.

## Kiểm thử nghiệm thu bắt buộc

1. **Máy mới đã cấp profile:** mở ứng dụng → đăng ký → đăng nhập → chia sẻ/tìm kiếm/tải tệp/thư mục; không phải mở Cấu hình nâng cao.
2. **TLS thật:** ghi nhận protocol/cipher suite của kết nối Peer–Tracker và Peer–Peer sau khi handshake; Wireshark không thấy plaintext nội dung file.
3. **Không hạ cấp:** sửa cấu hình cũ `TRACKER_TLS_ENABLED=false`, `P2P_TLS_ENABLED=false` hoặc file `tracker-settings.properties` từ bản cũ; bản production vẫn buộc TLS.
4. **Sai/trống chứng chỉ:** xóa/đổi truststore hoặc dùng certificate SAN sai, handshake bị chặn; không được chuyển TCP thường.
5. **Tính năng mạng:** thử 2 Peer LAN, thử Internet/Relay với dịch vụ Relay cấu hình thật và chính sách firewall; Relay không được dùng để bỏ TLS.
6. **Thử nghiệm đa máy & an toàn dữ liệu:** tải đồng thời/resume, xử lý mảnh sai, chống replay ticket, kiểm thử database và auth.
7. **Đổi khóa:** cần thủ tục thu hồi/luân chuyển cert/CA, Ed25519 ticket key riêng; bản này **chưa** tự làm thay người quản trị.

## Những việc chưa chứng minh ở môi trường tạo patch

- Gradle test/integration/UI tests cần tải Gradle/OpenJFX và phụ thuộc khác; môi trường offline hiện không có. Tránh báo “tất cả test pass”.
- Chưa chạy được trên Windows 10/2 máy thật nên **không khẳng định** toàn bộ tính năng đã nghiệm thu.
- CA/truststore phải được cấp phát, không tồn tại khóa CA tin cậy toàn Internet nào tự sinh mà mọi client đều an toàn tin ngay lập tức.

## Công nghệ và policy trong code

- `SecurityProfile`: production mặc định; mode không an toàn phải opt-in thêm `P2P_ALLOW_INSECURE_DEV=true` để thử cục bộ, không được ship.
- `PeerSettings`/`TrackerSettings` và `PeerP2pSocketConfig`: chế độ production ép TLS, bỏ qua toggle false cũ.
- `UserTrackerSettingsStore`: khi production ưu tiên endpoint tin cậy của bộ cài thay cho tùy chọn người dùng lưu từ bản cũ.
- `TrackerConnection`: từ chối TCP thường trong production.
- `LoginController`: TLS bắt buộc, cấu hình endpoint/truststore do nhà phát hành quản lý; không yêu cầu người dùng lưu cấu hình để đăng nhập.
- `SecureReleaseProvisioner.java`: công cụ **offline cho admin**, mỗi Peer có khóa riêng, không tạo self-signed trust-all cho toàn mạng.
