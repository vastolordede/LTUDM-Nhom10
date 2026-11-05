# ZIP17: Desktop trust import, Windows app-image packaging

- At login UI, `Import Tracker PUBLIC key` selects the public `.b64` file. Enter fingerprint obtained independently from Tracker operator. A wrong fingerprint MUST NOT be installed; a different existing trusted key is NOT overwritten silently. Restart Peer app after installing a new key.
- This import is not a substitute for authenticated Tracker TLS: production requires valid certificate/truststore configuration and a matching Tracker name; passwords are still provided securely outside the app.
- `scripts/build_windows.ps1` now selects the Gradle **versioned** peer-client JAR and uses a non-Application bootstrap entrypoint. It refuses to delete a previous app-image automatically.
- Build on a Windows host: `.\gradlew.bat clean :peer-client:installDist :peer-client:test`, `powershell -ExecutionPolicy Bypass -File scripts/build_windows.ps1`.
- Unzip `dist/FileShare-P2P-windows.zip`, launch the bundled executable on a CLEAN Windows VM without JDK/Gradle, test login, settings, public key import, search, share, download, resume and update. Artifact was NOT built/tested on Windows in this patch generation environment.
- TLS trust material / Ed25519 private keys must never be embedded in distributable ZIPs.

## Luồng sử dụng không cần terminal cho Peer

Người dùng cuối chỉ cần giải nén gói Windows app-image được cấp và mở `FileShare-P2P.exe`; không cần cài JDK/Gradle và không cần chạy CMD/PowerShell. Khi khởi chạy lần đầu:

1. Nhập Tracker host/domain và port trong cửa sổ đăng nhập, lưu cấu hình và dùng nút kiểm tra kết nối.
2. Nếu Tracker dùng TLS, bật TLS và nhập đường dẫn truststore được cấp. Mật khẩu truststore phải do quản trị viên cài đặt trước trong môi trường hệ điều hành được bảo vệ; ứng dụng không lưu mật khẩu này. Khởi chạy lại Peer sau khi quản trị viên cập nhật môi trường.
3. Chọn public key Tracker trong giao diện, nhập fingerprint nhận qua kênh tin cậy độc lập. Không chấp nhận fingerprint chỉ lấy từ cùng nguồn với tệp khóa. Sau khi nhập khóa, khởi động lại Peer.

Ứng dụng lần lượt chỉ ra bước cấu hình còn thiếu. Khi cấu hình Tracker chưa lưu, truststore TLS chưa sẵn sàng hoặc public key không hợp lệ/chưa tin cậy, nút đăng nhập/đăng ký không được bật. Không tắt TLS hoặc bỏ qua bước xác minh fingerprint để vượt qua lỗi.

## Việc quản trị viên vẫn cần thực hiện

Tracker không phải ứng dụng desktop cho người dùng cuối. Quản trị viên cần terminal hoặc công cụ vận hành tương đương để chuẩn bị/migrate PostgreSQL, cấu hình secret, khởi chạy Tracker, tạo signing key một lần, cài TLS keystore, build/phân phối Peer app-image và cấp public key/fingerprint cùng truststore qua kênh phù hợp. Lỗi khởi động Tracker được in thành một thông báo nguyên nhân và hướng xử lý; tiến trình trả exit code khác 0, không in stack trace hay chi tiết exception.

| Dữ liệu/secret | Ai giữ và ở đâu | GUI Peer có lưu không? |
|---|---|---|
| PostgreSQL password | Quản trị viên/DBA; secret store hoặc môi trường bảo vệ của tiến trình Tracker (`DB_PASSWORD`) | Không |
| Tracker TLS keystore password | Quản trị viên Tracker; secret store hoặc môi trường bảo vệ của Tracker | Không |
| Peer TLS truststore password | Quản trị viên cấp/provision; môi trường hệ điều hành bảo vệ (`TRACKER_TRUSTSTORE_PASSWORD`) | Không; GUI chỉ lưu đường dẫn truststore |
| Ed25519 ticket signing private key | Chỉ trên máy Tracker, thư mục được giới hạn quyền truy cập | Không |
| Tracker public key và fingerprint | Public key được quản trị viên phân phối; Peer nhập file và fingerprint qua GUI | Public key tin cậy được lưu cục bộ; không phải secret |
| Mật khẩu tài khoản Peer | Người dùng nhập khi đăng nhập | Không lưu trong cấu hình Peer |

Không đưa password, private key, truststore có secret hoặc tệp `.env` vào app-image/ZIP. Việc cấu hình OS secret phải tuân theo chính sách của máy/đơn vị quản trị; không đưa secret vào README, ticket, log hay kênh phân phối công khai.
