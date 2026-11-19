# E-Wallet Project (`vwallet`)

Hệ thống lõi ví điện tử (E-Wallet Core Backend Engine) hỗ trợ chuyển tiền nội bộ P2P, nạp tiền qua cổng thanh toán VNPay, cơ chế khóa chống Deadlock và hệ thống sổ cái kế toán kép (Double-Entry Ledger).

## 🚀 Tính năng cốt lõi
- **Chuyển tiền P2P (Peer-to-Peer):** 
  - Khóa bản ghi ví theo thứ tự tăng dần `min(idA, idB)` loại bỏ triệt để nguy cơ Deadlock khi chuyển tiền đối ứng đồng thời.
  - Kiểm tra số dư, xác thực mã PIN bằng BCrypt.
  - Cơ chế chống trùng lặp giao dịch (Idempotency) dựa trên Redis `SETNX`.
- **Sổ cái kế toán kép (Double-Entry Ledger):**
  - Mọi biến động số dư đều sinh ra cặp bút toán đối ứng `DEBIT` và `CREDIT` bất biến.
  - Kiểm tra và đảm bảo số dư không âm bằng ràng buộc vật lý `CHECK (balance >= 0)`.
- **Nạp tiền (Top-up) qua Cổng thanh toán (VNPay / Mock Gateway):**
  - Khởi tạo đơn hàng `PENDING` kèm URL ký số HMAC-SHA512.
  - Webhook callback IPN xử lý bất đồng bộ, đối soát chữ ký, cập nhật trạng thái và điều chuyển tiền từ `SYSTEM_WALLET`.

## 🛠 Công nghệ sử dụng
- **Backend:** Java 25, Spring Boot 3.x/4.x, Spring Data JPA / Hibernate, Spring Security.
- **Database & Cache:** MySQL 8.0 (InnoDB), Redis 7.
- **Môi trường Dev:** Docker Compose.

## 📄 Tài liệu kiến trúc & Kế hoạch
- **Đặc tả thiết kế (Design Spec):** [docs/superpowers/specs/2026-11-19-ewallet-core-design.md](docs/superpowers/specs/2026-11-19-ewallet-core-design.md)
- **Kế hoạch triển khai (Plan):** [docs/superpowers/plans/2026-11-19-ewallet-core.md](docs/superpowers/plans/2026-11-19-ewallet-core.md)
