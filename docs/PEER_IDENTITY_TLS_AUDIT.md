# Audit FileShare-P2P sau PR #67 – TLS định danh Peer (bản vá ứng viên)

> Nguồn: snapshot dựng lại từ `FileShare-P2P.zip` và các bản patch 01–06/REBASE trong cuộc trò chuyện. GitHub direct fetch không khả dụng. ZIP áp dụng phải kiểm tra hash từng file; nếu khác từ nhánh `dev`, dừng và rebase trước khi apply.

## Vấn đề đã chứng minh từ code cũ

- `scripts/SecureReleaseProvisioner.java` yêu cầu `PEER_ID=ADVERTISED_HOST`, tạo SAN `DNS:<host>` hoặc `IP:<ip>` trên chứng chỉ Peer.
- `PeerP2pSocketFactory.openClientSocket/wrapClient` đặt JSSE endpoint identification `HTTPS` theo host/IP được gọi đến, nên chứng chỉ Peer tạo cho IP khác có thể bị từ chối dù Peer vẫn dùng cùng key và peerId.
- `PeerIdentityStore/PeerKeyStore` đã có UUID và Ed25519 device key riêng; các dữ liệu này **ổn định theo tài khoản/máy**, khác với IP/port đang lưu trong `peer_sessions`.
- `FileSourceInfo` trước bản vá chưa mang khóa công khai của uploader. Tracker đã có `peers.public_key`; đây là dữ liệu thích hợp để neo danh tính của Peer bằng một kênh Tracker TLS đã xác thực.
- `ConnectionPlanner` thử Direct và chỉ thử Relay với lỗi vận chuyển; lỗi TLS không được bỏ qua. `RendezvousClient/Server` có REGISTER/CONNECT/ACCEPT và TLS cho control/relay; chưa xác nhận hoạt động thực tế với CGNAT, 4G hay chuyển mạng nóng.
- Trong code trước sửa, `DefaultAuthService` ghi đè `peers.public_key` vào mỗi lần đăng nhập, có thể làm mất ràng buộc danh tính cũ nếu tài khoản bị chiếm.

## Thiết kế đã triển khai trong ứng viên

1. Giữ Peer ID UUID và Ed25519 keypair hiện tại; khóa riêng sinh/lưu riêng theo từng Peer. **Không dùng chung keystore**.
2. Từ keypair Ed25519 đã có, Peer tự tạo chứng chỉ X.509v3 cho TLS bằng JCA/JDK 21, với SAN `peer-<UUID>.fileshare.invalid`; không chứa hoặc phụ thuộc địa chỉ IP. Chứng chỉ mới có hạn ngắn, được tạo lại khi khởi chạy nhưng dùng **cùng khóa công khai** ổn định. Không thêm thư viện và không cấp phát CA riêng cho Peer.
3. Tracker trả về `FileSourceInfo.peerPublicKey` từ `peers.public_key` trên kênh TLS đang xác thực. Downloader dùng **pin Ed25519 public key + SAN peerId + thời hạn, KU, EKU, chữ ký của chứng chỉ** trong `X509TrustManager`, trước khi truyền dữ liệu. Chứng chỉ sai/Peer ID sai bị từ chối. Không dùng trust-all, không hạ cấp TCP. Hostname verification của **Tracker vẫn bắt buộc**; với P2P, thay xác minh hostname bằng xác minh danh tính/key pin nghiêm ngặt hơn.
4. Đăng nhập bổ sung chữ ký Ed25519 từ khóa Peer (`PeerLoginProof`), timestamp và nonce chống replay. Tracker chỉ đăng ký khóa công khai nếu chưa có, hoặc nếu trùng khóa cũ (atomic trong JDBC). Không cho thay key bằng một lần login mà không có thủ tục đổi danh tính.
5. Dùng cùng pin trong kết nối Direct và TLS-in-Relay; Relay chỉ chuyển tiếp gói mã hóa và không quyết định danh tính nguồn.
6. Giao thức `MessageEnvelope` đổi **v3 → v4**, do `LoginRequest` và `FileSourceInfo` thay đổi. **Client v4 và Tracker v3 trên EC2 không tương thích**, phải nâng cấp Tracker có kiểm soát trước khi giao client mới.
7. Chuyển `SecureReleaseProvisioner` sang tạo **CLIENT_PUBLIC_BOOTSTRAP** (CA công khai của Tracker, ticket signing public key, địa chỉ Tracker) mà không cần tham số IP của Peer. Peer tự tạo khóa khi đăng nhập. Có công cụ `CreatePublicClientBootstrap.java` để dựng bootstrap từ **khóa công khai hiện có trên EC2**, không xoay CA hoặc ticket key.
8. Không thay `schema.sql`, migration v2–v9 hoặc mật khẩu/chứng chỉ EC2. Không can thiệp VPS.

## Tương thích và giới hạn cần kiểm thử

- Existing peers with mismatching stored Ed25519 keys are rejected; client may rotate **Peer UUID mới** after authenticated `PEER_KEY_MISMATCH` and user may need chia sẻ lại các file của Peer cũ. Không ghi đè key cũ.
- Tracker lưu IP/port từ kết nối/session đã xác thực; IP mới sẽ được nhận diện khi Peer **đăng nhập lại**. Chưa tự chứng minh rằng chuyển Wi-Fi/4G nóng tự động phục hồi mà không đăng nhập lại.
- Direct qua NAT/CGNAT phụ thuộc firewall, NAT mapping; cần thử Relay ở cổng 9001 trên hai mạng thật. Không đảm bảo Direct qua mọi mạng.
- Bộ cài portable dựa trên `installDist` còn cần Java Runtime 21; `jpackage` tự chứa runtime phải thử trên Windows 10 trước khi phát hành đại trà.
- Không thể chứng minh tương thích với git commit chính xác của `dev` khi không đọc trực tiếp GitHub. `apply_patch.py --require-base-hashes` được giữ để chặn nếu source khác mốc.
- Phải nghiệm thu Gradle full test và hai Peer trên Windows 10; chỉ có test JDK độc lập tại môi trường chuẩn bị.
