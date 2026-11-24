# FileShare-P2P — Local demo / AWS EC2 v4

## Phạm vi
Gộp patch 09 còn thiếu, patch 12 chưa áp dụng và xác thực Tracker với endpoint thay đổi. Mốc Windows đã push `099077f` (07,08,10,11). Không apply 09/12 riêng. EC2 source `d169da9`, hai container healthy, PostgreSQL 16.16; TLS 9000 và Rendezvous 9001. Ngày 24/11/2026 IP mới 32.237.93.188, chứng chỉ hiện có SAN IP 52.64.196.242. Không mặc định các IP đó còn đúng lần sau.

## Windows CMD (nhà phát triển)
Từ gốc repo, PostgreSQL local đã chạy, DB schema v9 và DB_PASSWORD trong .env hợp lệ. Không tạo lại DB đang có dữ liệu. JDK 21, Python 3.10+, Gradle wrapper. Lệnh mới:

```
scripts\run_local_server.cmd
scripts\run_local_client.cmd
scripts\run_local_client.cmd 2
```

Mỗi lệnh ở CMD riêng, server trước; chờ Tracker ready. Lần đầu tạo hồ sơ TLS local dưới work/local-demo; không dùng hồ sơ AWS. Peer 1/2 có user.home riêng, cổng 7001/7002. Không cần nhập CA, truststore hay key. PostgreSQL là điều kiện của server, không phải cấu hình người dùng. Giữ nguyên thư mục work/local-demo giữa các lần chạy. Không xóa private key để xử lý lỗi.

## AWS — quản trị viên, sau khi Windows test và push đạt
Kiểm tra instance ID i-010da5b48369aa363. Dùng SSH tin cậy, đúng máy. Không dùng git clean, docker compose down -v, schema.sql hay migration v2-v9. Không commit local-secrets hoặc .env. Không lấy private key về Windows. Dùng branch đã push, hoặc dev sau khi merge; xác minh SHA.

Trên EC2, lần lượt (mỗi lệnh một dòng):

```
cd ~/LTUDM-Nhom10/infra/staging
sudo docker compose ps -a
umask 077
BACKUP="$HOME/fileshare-backup-$(date +%Y%m%d-%H%M%S)"
mkdir -m 700 "$BACKUP"
cp -a .env local-secrets "$BACKUP/"
sudo docker compose exec -T postgres pg_dump -U fileshare -d fileshare_p2p -Fc > "$BACKUP/fileshare.dump"
sudo docker compose exec -T postgres pg_restore -l < "$BACKUP/fileshare.dump" >/dev/null
sudo docker inspect staging-tracker-1 --format '{{.Image}}' > "$BACKUP/old-image.txt"
git rev-parse HEAD > "$BACKUP/old-commit.txt"
```

Dừng nếu có lệnh lỗi. Backup chứa secret, không chia sẻ. Không tiếp tục nếu file dump rỗng. Chỉ cập nhật tracker:

```
cd ~/LTUDM-Nhom10
git status --short
git fetch origin
git log --oneline -5 origin/feat/peer-identity-tls-v4
git switch --detach origin/feat/peer-identity-tls-v4
cd infra/staging
sudo docker compose build tracker
sudo docker compose up -d --no-deps --force-recreate tracker
sudo docker compose ps -a
sudo docker logs --tail 40 staging-tracker-1
```

Chỉ chạy nếu branch đúng commit cuối, source tracked sạch, backup đã đạt. Xác minh tracker ready database=verified, TLS 9000, relay ON, postgres healthy. Không restart postgres. Nếu tracker fail, dừng, giữ postgres chạy; xem log. Rollback image: từ đúng staging dir, `sudo docker tag "$(cat "$BACKUP/old-image.txt")" staging-tracker` (kiểm tra tên image Compose trước khi dùng), sau đó `sudo docker compose up -d --no-build --no-deps --force-recreate tracker`. Không restore DB chỉ vì lỗi Tracker; bản vá không đổi schema. Giữ image cũ tới khi nghiệm thu.

## Cấp bộ tin cậy công khai chính thức từ EC2
Trên EC2, sau khi Tracker v4 ready, lấy public IP hiện tại từ console. Với chứng chỉ hiện tại, reference identity là IP SAN cũ, không phải IP kết nối. Script kiểm tra CA + SAN + chứng chỉ thực của cả 9000,9001 và chỉ xuất khóa PUBLIC Ed25519.

```
cd ~/LTUDM-Nhom10
sudo python3 scripts/export_ec2_public.py 32.237.93.188 52.64.196.242
```

Thay đối số đầu bằng IP hiện tại nếu khác. Chỉ đổi đối số thứ hai khi đã xác minh chứng chỉ được cấp lại từ CA tin cậy. Nếu export thất bại, không bỏ xác minh. Có thể cần CA công khai từ hồ sơ quản trị offline nếu PKCS12 không chứa root CA. Không tải private key hoặc mật khẩu lên chat. Copy đúng một dòng P2P_PUBLIC_V1:... từ SSH quản trị vào work/ec2-public.txt trên Windows. Token chỉ có dữ liệu công khai, nhưng nguồn token phải tin cậy; đối chiếu hai fingerprint qua SSH. Không nhận token từ Internet tùy ý.

Trên Windows CMD:

```
python scripts\import_ec2_public.py work\ec2-public.txt work\EC2_PUBLIC_BOOTSTRAP
scripts\build_ec2_client.cmd work\EC2_PUBLIC_BOOTSTRAP work\FileShare-P2P-EC2
```

So sánh fingerprint in ra với EC2. Output mới, không ghi đè bản cũ; nếu build lại dùng tên thư mục khác. Gửi toàn bộ ZIP release cho người dùng; giải nén, Java 21, double-click RUN_CLIENT.cmd hoặc `java -jar FileShare-P2P.jar` tại thư mục release. Không tách JAR khỏi lib và CLIENT_PUBLIC_BOOTSTRAP. Không cần IDE hoặc Python ở máy người dùng. Không chứa CA private key, Tracker private key hoặc DB password.

### Lý do không cấp lại cert khi IP đổi
TCP dial address và reference identity tách biệt chỉ khi admin cung cấp đồng thời reference identity và SHA-256 leaf pin. JSSE vẫn xác minh PKIX/CA và HTTPS SAN theo reference identity; pin chính xác được kiểm tra trước khi trao đổi thông điệp ứng dụng. Cả Tracker control và Rendezvous đều dùng cùng chính sách. Không trust-all, không TOFU, không fallback TCP. Không tự phát hiện IP mới khi EC2 Stop/Start. Khi IP đổi, admin xuất token mới và build lại bộ phân phối (hoặc dùng DNS ổn định trỏ về IP hiện tại; không dùng AWS auto-generated public DNS như tên cố định). Nếu đổi certificate thì cập nhật pin qua kênh tin cậy; không cần đổi Ed25519 key. Mục tiêu demo tiết kiệm chi phí, không hứa tự động DNS hay JRE đóng gói.

## Nghiệm thu
Local: hai tài khoản, share/search/download/resume, thư mục, SHA-256, history/unshare; thử hai máy EC2 khác Wi-Fi/4G, Direct/Relay khi khả thi, key sai phải bị từ chối. Ghi CHƯA KIỂM CHỨNG nếu chưa thử. Healthy container không đồng nghĩa E2E pass. Không mở port PostgreSQL. Khi xong demo có thể Stop đúng EC2; IP có thể thay đổi.
