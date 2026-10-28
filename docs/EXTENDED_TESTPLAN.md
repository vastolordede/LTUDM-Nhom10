# Kịch bản kiểm thử mở rộng (N01-N10, N14)

Ghi chú: các mục phụ thuộc D69, D70, D71, B04 chỉ đạt khi phần code tương ứng đã được nối vào.

| ID | Kịch bản | Cách thực hiện | Kết quả mong đợi | Hạng mục |
|---|---|---|---|---|
| N01 | Peer A ngắt giữa lúc publish file lớn | Share file vài GB, tắt Peer A (kill process) khi đang gửi metadata pages | File không xuất hiện trong Search; `publish_status` vẫn là `PUBLISHING`; share lại sau đó thì bù đủ pieces và chuyển `READY` | D60, D61, D69 |
| N02 | File lớn hơn 2 GB | `fsutil file createnew D:\test3gb.bin 3221225472`, share rồi tải từ Peer khác, so SHA-256 | Hash khớp, không lỗi JSON hoặc thiếu piece | D62, D63 |
| N03 | Persistent sharing | Share file, đóng ứng dụng, mở lại, login | Danh sách Share được khôi phục, Peer khác vẫn tải được; file đã xóa hoặc đổi kích thước bị loại | D70 |
| N04 | File aliases | Peer A share `a.txt`, Peer B share `b.txt` cùng nội dung | Search ra được cả `a` và `b`, tải về đúng nội dung | D71 |
| N05 | Thiếu dung lượng | Tải file lớn hơn dung lượng trống của ổ đích | Báo lỗi "Khong du dung luong", không tạo file dở | D66, D75 |
| N06 | Folder sharing | Chưa triển khai (D64, D65, D72) | Bỏ qua, ghi vào giới hạn đã biết | BONUS |
| N07 | Selective download | Chưa triển khai (D73) | Bỏ qua | BONUS |
| N08 | Tên file nguy hiểm | Nhập `CON`, `a?b`, `abc.`, `..\x`, `a/b` ở ô tên file | Mọi tên đều bị từ chối, không ghi ngoài thư mục đích | D74 |
| N09 | Peer ranking | A tải nhanh, B chậm, C gửi piece lỗi; chạy nhiều lần rồi truy vấn `v_peer_ranking` | Điểm A > B > C | B04 |
| N10 | Traffic emulator | Chạy 5, 10, 20 virtual peer, vừa tải vừa đổi trạng thái ONLINE/OFFLINE | Không deadlock, không lỗi session, SHA-256 mọi file đúng | B03 |
| N14 | Tương thích | Chia sẻ file đủ lớn để dùng metadata paging và adaptive chunking, tải từ 2 Peer | Không lỗi protocol, offset hay SHA-256 | D60-D63, D76 |
| N15 | Unshare rồi mở lại app | File không tự Share lại | D70 |
| N16 | Tắt Peer nguồn giữa lúc tải | Log "hoi lai Tracker", tải xong, SHA-256 đúng | D59 |
| N17 | Gửi request version khác 1 tới cổng upload | Trả lỗi UNSUPPORTED_PROTOCOL_VERSION | D76 |

## Câu SQL kiểm tra

-- N01: file nào đang publish dở
SELECT file_id, file_name, publish_status,
       (SELECT COUNT(*) FROM file_pieces p WHERE p.file_id = f.file_id) AS pieces, piece_count
FROM files f WHERE publish_status = 'PUBLISHING';

-- N04: các tên của cùng một file
SELECT f.file_hash, a.file_name FROM files f JOIN file_aliases a USING (file_id);

-- N09: bảng xếp hạng peer
SELECT * FROM v_peer_ranking ORDER BY score DESC;

-- Số nguồn online của mỗi file
SELECT file_id, COUNT(*) FROM v_file_sources GROUP BY file_id;