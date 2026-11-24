# Tracker Endpoint sau Stop/Start EC2 – Release Gate

**Trạng thái:** BẮT BUỘC KIỂM TRA KHI QUAY LẠI MÁY AWS. Tài liệu này không chứng nhận VPS đã hoạt động sau Start và không tự sửa tài nguyên AWS.

## 1. Tại sao việc Stop/Start cần kiểm tra

- EC2 `LTUDM-Nhom10-Tracker` được nhóm báo cáo dùng instance `i-010da5b48369aa363`, region `ap-southeast-2`. Địa chỉ `52.64.196.242` là **IP lúc thử nghiệm**, không được coi là IP hiện tại sau Stop/Start.
- Nếu dùng public IPv4 AWS tự cấp, sau **Stop → Start** AWS thường cấp **IP công khai mới**. `Reboot` khác với `Stop → Start`. Private IP, EBS, các volume và nội dung PostgreSQL trên EBS thường được giữ; compute không tính phí khi stopped, nhưng EBS vẫn tính phí.
- Chỉ một **Elastic IP** đã gắn đúng EC2/ENI mới giữ nguyên địa chỉ công khai qua Stop/Start. Elastic IP có **phí theo giờ ngay cả khi stopped**; cần kiểm tra Billing.

## 2. Phân biệt 3 địa chỉ/nhận dạng

1. **Tracker endpoint (FQDN/IP)**: nơi client kết nối TCP/TLS cổng 9000, Rendezvous/Relay cổng 9001. Hiện `CreatePublicClientBootstrap.java` và `SecureReleaseProvisioner.java` ghi `TRACKER_HOST` vào cấu hình, nên nếu là IP cũ thì client tiếp tục thử địa chỉ đã hết hiệu lực.
2. **Tracker TLS certificate name**: client kiểm tra SAN DNS/IP của chứng chỉ Tracker phải khớp với `TRACKER_HOST` đang kết nối. **Đổi DNS A không tự sửa SAN** nếu chứng chỉ cũ chỉ mang SAN IP cũ.
3. **Tracker ticket public key / CA trust**: danh tính ký ticket và gốc tin cậy phải được giữ/đối chiếu riêng. **Không tạo lại CA hoặc ký ticket chỉ vì đổi IP.**

## 3. Phương án ưu tiên (không phụ thuộc IP; ít chi phí duy trì)

Dùng **hostname cố định** do nhóm kiểm soát, ví dụ `tracker.<ten-mien-cua-nhom>` (ví dụ chỉ là ký hiệu, không phải tên miền đã đăng ký):

1. Chọn FQDN do nhóm có quyền quản trị, dùng cùng FQDN làm `TRACKER_HOST` khi tạo **CLIENT_PUBLIC_BOOTSTRAP**. Không gắn IP cố định vào bản cài Windows.
2. Trên Tracker, dùng chứng chỉ có **SAN=DNS:FQDN**. Nếu chứng chỉ hiện nay mang **SAN=IP:52.64.196.242**, phải **cấp lại chứng chỉ TLS máy chủ (leaf certificate)** với FQDN đúng, ký bằng CA được client tin cậy, bảo toàn CA root và Tracker Ed25519 ticket signing key khi có thể. Nếu không còn CA private key/tài sản ký hợp lệ, phải có kế hoạch chuyển đổi trust cho client; không được bỏ xác thực.
3. Mỗi khi Start EC2: lấy public IP **mới** trên AWS Console/CLI, cập nhật bản ghi DNS `A` của FQDN trỏ đến IP đó; chọn TTL thấp như 60–300 giây cho demo và chờ cache hết hạn.
4. Kiểm tra DNS trả về đúng IP, certificate SAN khớp **FQDN**, TLS handshake thành công và Tracker/Rendezvous chạy đúng cổng. Nếu hạ tầng chưa sẵn sàng, client báo không kết nối được, không tự chuyển sang plaintext.
5. DNS có thể cập nhật tự động bởi công cụ có quyền quản trị DNS trên máy quản trị/AWS, **không đặt DNS API token vào ứng dụng client**. Tự cập nhật DNS không thay thế xác thực TLS.

**Lưu ý:** Khi server tắt, FQDN vẫn có thể resolve tới IP trước đó, nhưng Tracker không hoạt động; ứng dụng phải xử lý `offline`/retry hợp lý. DNS chỉ giải quyết địa chỉ thay đổi, không khiến EC2 luôn chạy.

## 4. Phương án thay thế ít thao tác hơn

- **Elastic IP**: giữ một public IPv4 cố định cho Tracker. Phù hợp nếu muốn trình diễn nhanh, ít thay đổi code/cấu hình. Cần gắn EIP với instance/ENI đúng và **cấp/kiểm tra chứng chỉ có SAN cho EIP mới** nếu khác IP hiện có. Theo AWS VPC pricing, khoảng **$0.005/IP/giờ** (xấp xỉ $3.60/30 ngày) ngay cả khi instance stopped, chưa gồm EBS/những chi phí khác. Nếu chỉ mở VPS vài lần để demo, cân nhắc giải pháp DNS với chi phí tên miền/hosted zone riêng.
- **IP biến động và không có DNS/EIP**: chỉ phù hợp kiểm thử tạm; mỗi lần Start phải cập nhật `TRACKER_HOST` trong bootstrap phân phối lại, và xử lý SAN/cert nếu IP thay đổi. **Không đáp ứng yêu cầu "người dùng chỉ cần cài đặt và đăng nhập"**, không phải phương án phát hành.

## 5. Checklist khi quay lại máy quản lý AWS (không thực thi từ chat)

**Trên AWS CLI đã xác thực của chủ tài khoản, dùng lệnh chỉ đọc:**

```bash
aws ec2 describe-instances \
  --region ap-southeast-2 \
  --instance-ids i-010da5b48369aa363 \
  --query 'Reservations[0].Instances[0].{State:State.Name,PublicIP:PublicIpAddress,PrivateIP:PrivateIpAddress}' \
  --output table
```

- Xác nhận trạng thái `running`, public IP hiện tại, Elastic IP có hay không, Security Group port 9000/9001, DNS/TTL, và `TRACKER_HOST` mà các client đang sử dụng.
- Sau khi kiểm tra IP mới: SSH bằng IP/FQDN **đã xác minh**, không dùng mặc định `52.64.196.242`; chạy `docker compose ps`, kiểm tra cả Tracker và PostgreSQL healthy, log không có secret.
- Dùng `openssl s_client -connect <FQDN>:9000 -servername <FQDN>` và `openssl x509 -noout -subject -dates -ext subjectAltName` trên certificate public nhận được để đối chiếu SAN (không tắt xác minh trong ứng dụng).
- Backup PostgreSQL **v9 đang có dữ liệu** và các secret/cấu hình an toàn trước khi đổi chứng chỉ/cập nhật Tracker v4. Không chạy lại `schema.sql` hay migration v2–v9.
- Đảm bảo **client v4 chỉ kết nối Tracker v4** sau nâng cấp đã kiểm thử; thử đăng nhập, ticket Ed25519, TLS 9000, Relay 9001 và hai Peer ở khác mạng trước buổi báo cáo.
- Đánh dấu `CHƯA KIỂM CHỨNG` đối với kết quả chưa chạy thật sau lần Start mới.

## 6. Tình trạng của bộ patch 07–09

- **07**: nâng protocol v3 → v4; thay đổi danh tính và bằng chứng đăng nhập.
- **08**: pin chứng chỉ P2P theo Peer ID / khóa công khai, không dựa vào IP Peer.
- **09**: cấp **Tracker bootstrap công khai** (CA và ticket public key) với `TRACKER_HOST` là DNS hoặc IP, mặc định không có cơ chế cập nhật DNS tự động. Các công cụ đã nhận FQDN, vì vậy **không cần thay đổi thuật toán v4** để giải quyết IP EC2; cần hoàn thiện cấu hình DNS/cert và nghiệm thu. Công cụ `SecureReleaseProvisioner` tạo CA/signing keys MỚI, không được chạy lại trên Tracker đang hoạt động để "sửa IP".

**Quyết định release:** Chưa phát hành bản Windows cho người dùng cuối cho tới khi xác nhận Tracker endpoint ổn định, SAN đúng, TLS không hạ cấp và kiểm thử Start → đổi IP → cập nhật DNS → kết nối lại thành công.

## Tài liệu AWS

- EC2 Stop/Start: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/how-ec2-instance-stop-start-works.html
- AWS VPC public IPv4 pricing: https://aws.amazon.com/vpc/pricing/
- DNS TTL: https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/best-practices-dns.html
