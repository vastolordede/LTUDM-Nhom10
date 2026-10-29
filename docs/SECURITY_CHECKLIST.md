# Kiểm tra bảo mật trước khi mở Tracker ra ngoài LAN (D81)

## Đã có trong code
- Mật khẩu: BCrypt. Mọi request sau login yêu cầu sessionId hợp lệ (JdbcSessionPeerResolver).
- Peer-Tracker và Peer-Peer: TLS (TRACKER_TLS_ENABLED, P2P_TLS_ENABLED), chỉ chấp nhận TLS 1.2/1.3.
- Cổng Rendezvous 9001: dùng TLS khi Tracker TLS bật. Mọi lệnh yêu cầu sessionId hợp lệ.
- Relay chuyển tiếp dữ liệu đã mã hóa TLS đầu-cuối khi P2P TLS bật, Tracker không đọc được nội dung.
- Timeout: handshake 10s, kết nối nhàn rỗi 60s, chờ peer đích 8s, giới hạn 64 MiB mỗi kết nối relay.
- Giới hạn: tối đa 64 relay đồng thời, 256 thread Rendezvous, PROBE tối thiểu 3s giữa hai lần.
- Relay mặc định tắt (RELAY_ENABLED=false).

## Cần cấu hình khi triển khai
- Chỉ mở cổng 9000 và 9001 ra ngoài. KHÔNG mở cổng PostgreSQL 5432.
- PostgreSQL chỉ lắng nghe localhost (listen_addresses = 'localhost').
- Dùng mật khẩu DB mạnh, khác mật khẩu demo. Không commit .env, certs/.
- Bật TRACKER_TLS_ENABLED=true và P2P_TLS_ENABLED=true khi qua Internet.
- Tạo lại chứng chỉ với đúng IP/tên miền của Tracker và các máy demo.

## Giới hạn đã biết
- Chứng chỉ tự ký dùng chung: mọi Peer tin cùng một chứng chỉ, chưa xác thực từng Peer bằng chứng chỉ riêng.
- Chưa có TCP hole punching. Kết nối trực tiếp qua Internet cần NAT cho phép hoặc port-forward, nếu không dùng relay.
- Chưa có giới hạn tốc độ đăng nhập sai (brute force) ở mức ứng dụng.