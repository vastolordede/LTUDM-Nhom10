# TEST PLAN - D52-D68 (bai tap mon LTUDM)

## Muc dich

Kiem tra Peer GUI, chia se/tim kiem, download single/multi-source, history, adaptive chunking,
metadata pagination va cac xu ly an toan. Chi danh dau 'Da Hoan Thanh' sau khi qua test tren Windows.

## A. Truoc khi test

1. `git status` sach truoc khi apply patch va khong con rebase.
2. `java -version`: JDK 21; `./gradlew.bat --version` (CMD: `.\gradlew.bat --version`).
3. Database PostgreSQL theo `.env`; backup DB cu neu co, khong chay lai schema len DB dang dung.
4. Build: `.\gradlew.bat clean test` va `.\gradlew.bat build`.
5. Khong push neu mot trong hai lenh bao BUILD FAILED.

## B. Test GUI tu dau toi cuoi

| ID | Thao tac | Ket qua can dat | Checklist |
|---|---|---|---|
| G01 | Start tracker, start peer-client | Login hien thi | D52 |
| G02 | Login tai khoan dung, port 7001 | ONLINE va Peer Center mo ra | D52-D54 |
| G03 | Dong Peer Center bang X, bam Mo Peer Center | Tab mo lai, upload server van chay | D52-D54 |
| G04 | Vao tab Share, chon file txt <= 1MB, bam chia se | Thong bao thanh cong | D55 |
| G05 | Vao My Files sau G04 | File vua share co trong danh sach | D52,D55 |
| G06 | May B login tai khoan khac, Search theo ten file | Tim thay file, hien source A dang ONLINE | D52,D56,D59 |
| G07 | May B chon ket qua, bam Tai file da chon | Tu dien file ID/name, chuyen tab Download | D56 |
| G08 | May B chon thu muc, bam Bat dau tai | File tai ve, SHA-256 dung | D56-D58 |
| G09 | Mo History o B | Mot ban ghi COMPLETED dung file ID, transfer ID | D57-D58 |
| G10 | May A My Files -> Ngung chia se | May B refresh sources se khong thay A | D55,D59 |
| G11 | May C tai tu A + B da share cung file | Multi-source hoat dong, hash trung | D59,D67 |
| G12 | Download dang chay -> Tam dung -> Resume | Phan da kiem tra hash duoc su dung lai | D67 |
| G13 | Dong/bat Tracker khi peer chay | Theo doi reconnect va status | D59,D67 |
| G14 | Tai trung ten file da co | Hien hop thoai xac nhan truoc overwrite/resume | D66 |
| G15 | Nhap filename `../a.txt` hoac `folder/a.txt` | Tu choi ten file khong an toan | D66 |
| G16 | Logout | Da log out, Peer Center/Upload server dung | D54 |
| G17 | Peer A share file, rồi logout hoặc tắt hẳn Peer A, đợi quá 30 giây. Peer B Search lại. | Số nguồn của file giảm, A không còn trong danh sách nguồn | D59 |
| G18 | Chọn thư mục đích gần đầy hoặc file lớn hơn dung lượng trống rồi bấm tải | Báo lỗi "Khong du dung luong" trước khi tải, không tạo file dở | D66, D75 |
| G19 | Peer C đang tải từ A và B, tắt A giữa chừng | Tải tiếp từ B, hoàn thành, SHA-256 đúng | D59, S32 |
| G20 | Nhập tên file `CON`, `a?b`, `abc.` ở ô tên file | Bị từ chối với thông báo rõ ràng | D74 |
| G21 | Tải file 3 GB (hoặc lớn nhất máy cho phép) giữa 2 Peer, so SHA-256 | Hash khớp, không lỗi JSON/bộ nhớ | D63, N02 |
| G22 | Tải dở rồi đóng ứng dụng, mở lại và tải cùng file vào cùng đường dẫn | Resume, chỉ tải các piece còn thiếu | D63, S41 |

## C. Test metadata lon

1. `MetadataPageIntegrationTest` tao 2401 records gia lap de test P2P paging ma khong can file 1GB.
2. `AdaptiveChunkingTest` kiem tra moc kich thuoc piece va chia piece count khong overflow.
3. Doi voi test gui file lon: test 50MB truoc; chi test >2GB neu may con du RAM/dung luong.
4. Kiem tra `file_pieces` DB co day du pieces voi file moi (piece_index 0..piece_count-1).
5. Sau download, so san SHA-256 de kiem tra chinh xac.

## D. DB queries read only

```sql
SELECT file_id,file_name,file_size,piece_size,piece_count FROM files ORDER BY file_id DESC LIMIT 10;
SELECT f.file_id, f.piece_count, COUNT(fp.piece_index) AS hashes_saved
FROM files f LEFT JOIN file_pieces fp ON fp.file_id=f.file_id
GROUP BY f.file_id,f.piece_count ORDER BY f.file_id DESC LIMIT 10;
SELECT transfer_id,file_id,status,bytes_received,started_at,completed_at
FROM transfer_sessions ORDER BY started_at DESC LIMIT 10;
SELECT peer_id,file_id,availability_status,is_sharing FROM peer_files;
```

## E. Cac han che / viec de sau

- D64, D65: Share/Download ca thu muc giu lai cau truc file: CHUA IMPLEMENT; can thiet ke folder manifest, nhan dien duplicate/hash.
- D67: da co huong dan, ket qua end-to-end 3 may phai do nguoi dung test; chua du dieu kien check Da Hoan Thanh.
- TLS Peer-Peer tren Login van dung plainTcp; neu muon demo TLS can khai bao va truyen config rieng.
- DB va Tracker khong luu binary; may chia se phai giu file goc va cong upload mo.
- Metadata page upload luu theo trang, trong luc gui chua xong co the co metadata chua day du. Khong demo voi file lon neu chua test ky.
- Download file hien cho nguoi dung xac nhan khi file trung ten; resume van dua tren piece SHA-256.
- Chua co gioi han file lon toi da da duoc do thuc te; file qua lon van co the vuot RAM, so piece cho phep va dia.

## F. Phan cum commit trong mot branch (sau khi test OK)

**C1 UI D52-D56**: LoginController, PeerApplication, TransferDashboardWindow, ShareController,
SearchController, MyFilesController, login-view.fxml, search-view.fxml.

**C2 Tracker/protocol D57,D59,D60-D63**: DTO + MessageType, FileChunker,
FileMetadataBuilder, ShareFileClientService, PeerUploadServer, PeerDownloadClient,
MultiSourceDownloadEngine, TrackerApplication, TrackerRequestDispatcher, TrackerErrorResponses,
FileCatalogService, FileRepository/JdbcFileRepository, PeerFileRepository/JdbcPeerFileRepository,
MetadataPageIntegrationTest, AdaptiveChunkingTest.

**C3 Download safety + Docs D58,D66-D68**: DownloadController, README.md,
`docs/POST_PATCH_TESTPLAN.md`.

KHONG them `.env`, `work/`, `build/`, `*.p12` vao git.
