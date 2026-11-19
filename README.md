# E-Wallet Project (vwallet)

Backend ví điện tử xây dựng bằng Java và Spring Boot, hỗ trợ các tính năng ví cốt lõi: chuyển tiền P2P, nạp tiền qua VNPay/mock gateway và ghi sổ kế toán kép. Hệ thống sử dụng MySQL cho dữ liệu giao dịch, Redis cho idempotency và Docker Compose để khởi chạy môi trường phát triển.

## Tính năng

- Chuyển tiền P2P giữa hai ví người dùng.
- Xác thực mã PIN bằng BCrypt.
- Chống trùng lặp yêu cầu bằng `Idempotency-Key` và Redis `SETNX`.
- Khóa ví theo thứ tự ID tăng dần để tránh deadlock trong các giao dịch hai chiều đồng thời.
- Ghi nhận giao dịch theo mô hình double-entry ledger với bút toán `DEBIT` và `CREDIT`.
- Chặn số dư âm bằng kiểm tra nghiệp vụ và ràng buộc `CHECK (balance >= 0)` trong MySQL.
- Khởi tạo lệnh nạp tiền và xử lý webhook VNPay với chữ ký HMAC-SHA512.
- API tra cứu ví và trạng thái giao dịch.

## Công nghệ

- Java 25
- Spring Boot 4.1.2
- Spring Data JPA / Hibernate
- Spring Security và BCrypt
- MySQL 8.0 (InnoDB)
- Redis 7
- Docker Compose
- Maven
- JUnit 5 và Mockito

## Cấu trúc dự án

```text
.
├── docker/
│   └── docker-compose.yml       # MySQL 8.0 và Redis 7
├── docs/
│   └── superpowers/
│       ├── specs/               # Đặc tả thiết kế
│       └── plans/               # Kế hoạch triển khai
└── ewallet-backend/
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/com/meme/vwallet/
        │   └── resources/
        └── test/
```

## Yêu cầu hệ thống

- JDK 25
- Docker và Docker Compose
- Maven (hoặc dùng Maven Wrapper trong `ewallet-backend`)

## Khởi chạy môi trường

Từ thư mục gốc của repository:

```bash
cd docker
docker compose up -d
```

Dịch vụ được khởi tạo:

| Dịch vụ | Host | Port |
|---|---|---|
| MySQL | `localhost` | `3306` |
| Redis | `localhost` | `6379` |

Thông tin mặc định:

- Database: `vwallet`
- Username: `vwallet_user`
- Password: `vwallet_password`
- MySQL root password: `rootpassword`

## Chạy backend

```bash
cd ewallet-backend
./mvnw spring-boot:run
```

Ứng dụng mặc định chạy tại `http://localhost:8080`.

## Chạy kiểm thử

```bash
cd ewallet-backend
./mvnw test
```

Repository đã có các test cho controller, service, repository, security, cùng các integration test về deadlock P2P và idempotency nạp tiền.

## API chính

Tất cả API ví giả định nhận định danh người dùng qua header `X-User-Id` trong giai đoạn phát triển.

### Chuyển tiền P2P

```http
POST /api/v1/transfers
Content-Type: application/json
X-User-Id: 2
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000

{
  "receiverPhone": "0987654321",
  "amount": 100000,
  "pin": "123456",
  "description": "Chuyen tien"
}
```

### Nạp tiền

```http
POST /api/v1/payments/topup
Content-Type: application/json
X-User-Id: 2

{
  "amount": 500000
}
```

### Các endpoint tra cứu

```http
GET /api/v1/wallets/me
X-User-Id: 2

GET /api/v1/transactions/{id}

POST /api/v1/payments/webhook
GET  /api/v1/payments/vnpay-return
```

Chi tiết request/response được mô tả trong [đặc tả thiết kế](docs/superpowers/specs/2026-11-19-ewallet-core-design.md).

## Tài liệu

- [Đặc tả thiết kế](docs/superpowers/specs/2026-11-19-ewallet-core-design.md)
- [Kế hoạch triển khai](docs/superpowers/plans/2026-11-19-ewallet-core.md)
