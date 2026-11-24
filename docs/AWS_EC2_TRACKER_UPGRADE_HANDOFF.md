# VIỆC CẦN LÀM KHI QUAY LẠI MÁY QUẢN LÝ AWS

**Mục đích:** cập nhật Tracker để tương thích với Client dùng giao thức v4 và chứng chỉ TLS gắn với Peer ID ổn định. **Không chạy lệnh nào trong tài liệu này trên môi trường AWS hiện tại nếu chưa có sao lưu/phê duyệt.**

## Môi trường do nhóm báo cáo (chưa tự đăng nhập kiểm chứng)

- AWS EC2 `LTUDM-Nhom10-Tracker`, **IP trước khi Stop `52.64.196.242` (không giả định còn đúng sau Start)**, region `ap-southeast-2`, Ubuntu 24.04 LTS, instance `t3.small`, RAM 2 GiB.
- Source `~/LTUDM-Nhom10` (nhánh `dev`), Compose `~/LTUDM-Nhom10/infra/staging`.
- Container `staging-tracker-1`, `staging-postgres-1`, PostgreSQL 16, database `fileshare_p2p`, schema đã migrate tới **v9**.
- Tracker TLS port **9000**, Rendezvous/Relay port **9001**; Security Group được thông báo mở 22, 9000, 9001.
- Tracker và PostgreSQL từng được báo healthy trước bản vá. Chưa xác minh trạng thái sau đó.

## Bổ sung bắt buộc – EC2 Stop/Start có thể đổi public IP

**Không sử dụng `52.64.196.242` như địa chỉ cố định nữa.** Nếu đó là public IPv4 tự cấp và EC2 bị Stop rồi Start, AWS cấp public IP mới. Khuyến nghị gán tên miền/FQDN ổn định làm `TRACKER_HOST`, cập nhật DNS A sau khi Start; chứng chỉ Tracker phải có SAN của FQDN, không chỉ SAN của IP cũ. **Đổi DNS một mình không giải quyết mismatch chứng chỉ**. Nếu chọn Elastic IP, địa chỉ ổn định nhưng có phí giờ kể cả khi instance stopped. Xem hướng dẫn chi tiết: `docs/TRACKER_PUBLIC_ENDPOINT_STOP_START.md`.

Trong bản phát hành, Tracker và Peer vẫn xác minh TLS đầy đủ, không bỏ chứng chỉ để kết nối. Không chạy lại `SecureReleaseProvisioner` trên Tracker hiện hữu vì sẽ xoay CA và Ed25519 signing key.

## Thay đổi ảnh hưởng đến Tracker

1. `MessageEnvelope.CURRENT_VERSION` nâng từ **3 → 4**. Client mới cần Tracker mới; **không bật client v4 với Tracker v3**.
2. `LoginRequest` có timestamp, nonce, chữ ký chứng minh sở hữu khóa Ed25519; Tracker bắt buộc xác minh khi chạy production.
3. `PeerRepository.claimPublicKey` ngăn ghi đè khóa hiện có; `FileSourceService` trả về uploader `peerPublicKey` từ DB qua Tracker TLS để client pin chứng chỉ Peer.
4. **Không có migration DB mới**; DB v9 giữ nguyên. Không chạy lại schema.sql hay migration v2–v9 trên database có dữ liệu.
5. Không cần thay TLS certificate/CA của Tracker hoặc Ed25519 ticket signing key nếu hiện vẫn hợp lệ và được các client tin cậy.

## Quy trình an toàn trên máy quản lý AWS (chỉ khi có quyền và backup)

1. Đối chiếu trạng thái Git, image/container đang chạy, phiên bản giao thức, kiểm tra cấu hình và **định danh tất cả secret đang mount**. Không đưa secret vào Git, log hoặc chat.
2. Sao lưu PostgreSQL (pg_dump có kiểm tra restore ở nơi tách biệt) và cấu hình Docker Compose, keystore/CA public, thông tin phiên bản; kiểm soát quyền truy cập bản backup.
3. Xác minh tính đồng nhất giữa mã `dev` sau PR mới và source trên VPS. Không giả định push/merge local tự triển khai lên EC2.
4. Đối chiếu và backup public certificate CA, `tracker-public.key.b64`, fingerprint. **Không tạo lại CA/tracker ticket signing key** nếu không có kế hoạch xoay vòng và cập nhật đồng bộ toàn bộ client.
5. Sau Start, kiểm tra IP công khai **mới** / Elastic IP, DNS của FQDN, SAN cert TLS và `TRACKER_HOST`. Chuẩn bị `CLIENT_PUBLIC_BOOTSTRAP` từ CA public và ticket public key **hiện có** với **FQDN ổn định**; không cần khóa riêng Tracker. Nếu SAN chỉ mang IP cũ, cấp lại **leaf cert Tracker** bằng CA tin cậy theo kế hoạch trước khi phân phối client; không lấy trust từ HTTP không xác thực.
6. Cập nhật source đúng commit đã kiểm thử; review ảnh hưởng đến `infra/staging/compose.yml`, environment, mounts và không chạm tới volume PostgreSQL.
7. Nếu không có migration mới, **không** chạy `schema.sql` hoặc `migration_v2..v9` lại. Chỉ kiểm tra schema hiện có theo quy trình read-only.
8. Rebuild image Tracker, triển khai rolling/scheduled maintenance sau backup; **restart riêng Tracker** theo Compose mà không làm mất volume database (không dùng `down -v`).
9. Xác minh `staging-postgres-1` healthy, bảng dữ liệu không bị thay đổi; Tracker healthy; TLS 9000 xác thực hostname đúng; cổng 9001 TLS và Relay ON; protocol v4.
10. Trên hai máy thật với profile riêng, thử cùng LAN, hai Wi-Fi, 4G↔Wi-Fi, NAT/CGNAT và đổi địa chỉ IP rồi đăng nhập lại. Xác nhận Direct/Relay, ticket, SHA-256, tải file/thư mục, resume, chặn chứng chỉ sai/Peer key giả.
11. Nếu có lỗi, rollback **Tracker image/source + cấu hình** về phiên bản trước, giữ nguyên database; không dùng client v4 cho Tracker đã rollback về v3.
12. Sau nghiệm thu, lưu kết quả test thật và ảnh làm bằng chứng; cập nhật báo cáo FINAL và Google Sheets.

## Lệnh kiểm tra tham khảo (không tự chạy)

```bash
# Kiểm tra public IP sau khi bật instance (AWS CLI trên máy quản lý):
aws ec2 describe-instances --region ap-southeast-2 --instance-ids i-010da5b48369aa363 --query 'Reservations[0].Instances[0].{State:State.Name,PublicIp:PublicIpAddress}' --output table
# Thay placeholder bằng endpoint thực đã xác minh:
ssh ubuntu@<FQDN-hoac-public-IP-moi-da-xac-minh>
cd ~/LTUDM-Nhom10
 git rev-parse HEAD
 git status --short
cd infra/staging
 docker compose ps
 docker compose logs --tail=100 tracker
```

Lưu ý: Compose service có thể không tên `tracker`; kiểm tra `docker compose config --services` trước. Lệnh backup/restart phải dựa vào vị trí credentials/volume thực tế, không đoán hoặc nhúng mật khẩu trong tài liệu.

## Cần chuyển sang cuộc hội thoại AWS

Gửi file hướng dẫn này cùng commit hash PR mới, tài liệu `docs/PEER_IDENTITY_TLS_AUDIT.md`, bản client đã qua test, SHA-256/cipher suite chứng minh TLS, và các log không chứa secret. **Không gửi private key, `.env` thật hoặc keystore cá nhân.**
