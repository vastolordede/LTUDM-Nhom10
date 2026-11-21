# Bàn giao bản vá cuối – FileShare-P2P (N commit → 1 push → 1 PR → Google Sheets)

**Nguồn đối chiếu:** `FileShare-P2P.zip` do người dùng gửi. Không thể xác minh SHA/branch của `https://github.com/vastolordede/LTUDM-Nhom10` trong môi trường này do GitHub DNS inaccessible; tuyệt đối **không** kết luận ZIP trùng GitHub.

## Đã thực hiện trên bản sao cục bộ

1. Snapshot mã nguồn ZIP đã được đặt làm mốc cơ sở cục bộ, không phải GitHub commit.
2. Các thay đổi chia thành commit logic, tạo `.patch` bằng `git format-patch`.
3. Đã kiểm tra `git diff --check`, XML FXML, biên dịch các lớp Java bảo mật/transport với JDK 21 và các JAR có sẵn; đã chạy smoke test TLS/SAN/legacy config.
4. Gradle full test, JavaFX UI, PostgreSQL integration và multi-peer Windows thực tế **chưa có kết quả** vì không có Gradle distribution và môi trường GUI/DB cần thiết. Không push tự động khi test chưa đạt.

## Áp dụng lên repository đích trên Windows 10

Trong một clone GitHub **sạch và cập nhật** (tên branch phải căn cứ vào repo thực tế), chạy:

```powershell
# 0. fetch + xác định commit/branch hiện tại; lưu phương án quay lui.
git fetch origin
git status --short
git switch -c feat/secure-by-default-final origin/main

# 1. DRY-RUN trên worktree/clone tạm trước; patch phải áp dụng sạch.
# Bộ patch nằm trong thư mục PATCHES, có thứ tự 0001, 0002, ...
# Nếu patch conflict: KHÔNG ghi đè, đối chiếu repo với ZIP rồi mới xử lý.

# 2. APPLY n commit logic (không squash khi chưa kiểm tra)
git am --3way D:\Patch\PATCHES\*.patch

# 3. TEST, BUILD trên Windows 10 với JDK 21/PostgreSQL và các Peer test.
.\gradlew.bat clean test
.\gradlew.bat :peer-client:installDist
# chạy test chứng chỉ sai SAN, thiếu truststore, không downgrade,
# đồng thời thử đăng ký, chia sẻ, tìm kiếm, tải thư mục giữa ít nhất 2 Peer.

# 4. Nếu tất cả gate PASS và cây làm việc sạch:
git status --short
git log --oneline origin/main..HEAD

# 5. Đúng MỘT PUSH khi đã đạt gate:
git push -u origin feat/secure-by-default-final

# 6. Tạo đúng MỘT PR bằng GitHub UI hoặc gh (nếu có sẵn).
# 7. Google Sheets: ghi branch, commit SHAs, PR URL, test evidence, owner, status.
```

Nếu nhánh chính không phải `main`, thay đúng tên đã xác minh. `git am` phải được dry-run trên clone/worktree tạm trước khi áp dụng lên branch làm việc. **Không** push khi phát hiện lệch version hoặc test fail.

## Các tiêu chí nghiệm thu trước khi thông báo “sản phẩm hoàn thành”

- Một Peer **đã được cấp phát chứng chỉ** trên Windows 10 có thể mở app, đăng ký, đăng nhập và sử dụng mà không tự chọn TLS/keystore.
- Trường hợp thiếu truststore, cert sai, tên SAN sai hoặc ticket-key sai bị **từ chối**, không hạ cấp.
- Đã thử Tracker–Peer và Peer–Peer (direct + relay nếu hạ tầng được cấp) qua TLS; ghi nhận cipher suite và bằng chứng Wireshark.
- CSDL, đăng nhập, chia sẻ, tải file/thư mục, resume, tốc độ/đa nguồn, đồng thời và lịch sử đều có bằng chứng thực nghiệm.
- Mọi file chứng chỉ/thư mục `ADMIN_KEEP_PRIVATE`, `TRACKER_INSTALL`, `.env` chứa mật khẩu/khóa riêng không được commit hay gửi public.

## Trạng thái workflow tại lần bàn giao này

| Công đoạn | Trạng thái |
|---|---|
| Source ZIP → local patch | Có |
| Commit logic cục bộ | Có |
| Dry-run policy/TLS smoke | Có, phạm vi giới hạn |
| Gradle full tests + Windows E2E | Chưa chạy được |
| Push lên GitHub | Chưa (không có kết nối/ủy quyền) |
| Pull Request trên GitHub | Chưa |
| Google Sheets cập nhật | Chưa (không có đường dẫn/quyền ghi) |
