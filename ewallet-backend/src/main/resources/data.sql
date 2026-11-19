-- Seed Users
INSERT IGNORE INTO users (id, phone_number, full_name, password_hash, pin_hash, status)
VALUES (1, '0000000000', 'SYSTEM_USER', '$2a$10$uYvDISDqtjS1r0EEE3jdVOXaV7/9eiVflcEWiFyXpKrhoRKnK4tNW', NULL, 'ACTIVE');

INSERT IGNORE INTO users (id, phone_number, full_name, password_hash, pin_hash, status)
VALUES (2, '0901234567', 'Nguyen Van A', '$2a$10$uYvDISDqtjS1r0EEE3jdVOXaV7/9eiVflcEWiFyXpKrhoRKnK4tNW', '$2a$10$uYvDISDqtjS1r0EEE3jdVOXaV7/9eiVflcEWiFyXpKrhoRKnK4tNW', 'ACTIVE');

INSERT IGNORE INTO users (id, phone_number, full_name, password_hash, pin_hash, status)
VALUES (3, '0987654321', 'Tran Thi B', '$2a$10$uYvDISDqtjS1r0EEE3jdVOXaV7/9eiVflcEWiFyXpKrhoRKnK4tNW', '$2a$10$uYvDISDqtjS1r0EEE3jdVOXaV7/9eiVflcEWiFyXpKrhoRKnK4tNW', 'ACTIVE');

-- Seed Wallets
INSERT IGNORE INTO wallets (id, user_id, balance, currency, status, version)
VALUES (1, 1, 1000000000000, 'VND', 'ACTIVE', 0);

INSERT IGNORE INTO wallets (id, user_id, balance, currency, status, version)
VALUES (2, 2, 1000000, 'VND', 'ACTIVE', 0);

INSERT IGNORE INTO wallets (id, user_id, balance, currency, status, version)
VALUES (3, 3, 500000, 'VND', 'ACTIVE', 0);
