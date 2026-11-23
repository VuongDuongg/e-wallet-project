package com.meme.vwallet.integration;

import com.meme.vwallet.dto.request.TopupRequest;
import com.meme.vwallet.dto.response.TopupResponse;
import com.meme.vwallet.dto.response.VnpayIpnResponse;
import com.meme.vwallet.entity.LedgerEntry;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.EntryType;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.repository.LedgerEntryRepository;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.service.IdempotencyService;
import com.meme.vwallet.service.PaymentService;
import com.meme.vwallet.service.VnpayService;
import com.meme.vwallet.support.SecurityTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TopupIdempotencyIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private VnpayService vnpayService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @MockitoBean
    private IdempotencyService idempotencyService;

    private User userA;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();

        // Ensure system wallet exists
        User systemUser = userRepository.findById(1L)
                .orElseGet(() -> userRepository.save(User.builder()
                        .phoneNumber("0000000000")
                        .fullName("SYSTEM_USER")
                        .passwordHash("$2a$10$uYvDISDqtjS1r0EEE3jdVOXaV7/9eiVflcEWiFyXpKrhoRKnK4tNW")
                        .status("ACTIVE")
                        .build()));

        Wallet systemWallet = walletRepository.findById(1L)
                .orElseGet(() -> Wallet.builder()
                        .user(systemUser)
                        .balance(1_000_000_000_000L)
                        .currency("VND")
                        .status(WalletStatus.ACTIVE)
                        .build());
        systemWallet.setBalance(1_000_000_000_000L);
        walletRepository.save(systemWallet);

        // Prepare User A and wallet
        userA = userRepository.findByPhoneNumber("0901234567")
                .orElseGet(() -> userRepository.save(User.builder()
                        .phoneNumber("0901234567")
                        .fullName("Nguyen Van A")
                        .passwordHash("$2a$10$uYvDISDqtjS1r0EEE3jdVOXaV7/9eiVflcEWiFyXpKrhoRKnK4tNW")
                        .status("ACTIVE")
                        .build()));

        Wallet userWallet = walletRepository.findByUserId(userA.getId())
                .orElseGet(() -> Wallet.builder()
                        .user(userA)
                        .currency("VND")
                        .status(WalletStatus.ACTIVE)
                        .build());
        userWallet.setBalance(100_000L);
        walletRepository.save(userWallet);

        SecurityTestUtils.setAuthenticatedUser(userA.getId(), userA.getPhoneNumber(), userA.getFullName());
    }

    @AfterEach
    void tearDown() {
        SecurityTestUtils.clearSecurityContext();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.findById(1L).ifPresent(w -> {
            w.setBalance(1_000_000_000_000L);
            walletRepository.save(w);
        });
        if (userA != null) {
            walletRepository.findByUserId(userA.getId()).ifPresent(w -> {
                w.setBalance(1_000_000L);
                walletRepository.save(w);
            });
        }
    }

    @Test
    @DisplayName("5 concurrent webhook arrivals with same vnp_TxnRef credit wallet exactly once with 2 ledger entries and all return RspCode 00")
    void testConcurrentWebhookIdempotency() throws Exception {
        long topupAmount = 500_000L;
        Wallet initialWallet = walletRepository.findByUserId(userA.getId()).orElseThrow();
        long initialBalance = initialWallet.getBalance();

        // 1. Initiate a PENDING top-up transaction for User A (500,000 VND)
        SecurityTestUtils.setAuthenticatedUser(userA.getId(), userA.getPhoneNumber(), userA.getFullName());
        TopupRequest request = TopupRequest.builder()
                .amount(topupAmount)
                .description("Topup test idempotency")
                .build();
        TopupResponse topupResponse = paymentService.initiateTopup(userA.getId(), request, "127.0.0.1");
        Long txId = topupResponse.getTransactionId();
        assertThat(txId).isNotNull();

        // Verify initial transaction status is PENDING
        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PENDING);

        // 2. Generate valid webhook parameters matching VnpayService HMAC-SHA512
        Map<String, String> webhookParams = buildSignedWebhookParams(txId, topupAmount);
        assertThat(vnpayService.verifySignature(webhookParams))
                .as("Constructed webhook params must have valid signature")
                .isTrue();

        // 3. Concurrently fire 5 threads calling paymentService.processWebhook(params) with exact same vnp_TxnRef
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<VnpayIpnResponse> responses = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    VnpayIpnResponse ipnResponse = paymentService.processWebhook(webhookParams);
                    responses.add(ipnResponse);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityTestUtils.clearSecurityContext();
                    doneLatch.countDown();
                }
            });
        }

        // Wait until all 5 threads are ready
        boolean ready = readyLatch.await(10, TimeUnit.SECONDS);
        assertThat(ready).as("All 5 threads must be ready").isTrue();

        // Release all 5 threads simultaneously
        startLatch.countDown();

        // Wait for all 5 threads to complete
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        assertThat(finished).as("All 5 webhook threads must complete within timeout").isTrue();
        executor.shutdown();

        // Assertions:
        // No exceptions thrown
        assertThat(errors).as("No threads should throw unhandled exceptions").isEmpty();
        assertThat(responses).as("Exactly 5 responses must be captured").hasSize(threadCount);

        // All 5 threads return RspCode: 00
        for (VnpayIpnResponse ipnResponse : responses) {
            assertThat(ipnResponse.getRspCode())
                    .as("Every concurrent webhook call must return RspCode 00")
                    .isEqualTo("00");
        }

        // User A wallet balance is credited exactly once (increased by 500,000 VND, NOT 2,500,000 VND)
        Wallet finalWallet = walletRepository.findByUserId(userA.getId()).orElseThrow();
        assertThat(finalWallet.getBalance())
                .as("Wallet balance must be credited exactly once by 500,000 VND (100,000 + 500,000 = 600,000)")
                .isEqualTo(initialBalance + topupAmount);

        // System wallet balance is debited exactly once
        Wallet finalSysWallet = walletRepository.findById(1L).orElseThrow();
        assertThat(finalSysWallet.getBalance())
                .as("System wallet must be debited exactly once by 500,000 VND")
                .isEqualTo(1_000_000_000_000L - topupAmount);

        // Exactly 2 ledger entries created for this transaction (1 DEBIT system, 1 CREDIT user)
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(txId);
        assertThat(entries)
                .as("Exactly 2 ledger entries must be recorded for this topup transaction")
                .hasSize(2);

        assertThat(entries).extracting(LedgerEntry::getEntryType)
                .as("Ledger entries must contain exactly 1 DEBIT and 1 CREDIT")
                .containsExactlyInAnyOrder(EntryType.DEBIT, EntryType.CREDIT);

        assertThat(entries)
                .as("Each ledger entry amount must equal the topup amount")
                .allMatch(e -> e.getAmount().equals(topupAmount));

        // Transaction status must be COMPLETED
        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.COMPLETED);
    }

    private Map<String, String> buildSignedWebhookParams(Long txId, Long amountVnd) {
        long vnpAmount = amountVnd * 100L;
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TmnCode", vnpayService.getTmnCode());
        params.put("vnp_Amount", String.valueOf(vnpAmount));
        params.put("vnp_TxnRef", String.valueOf(txId));
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionNo", "VNPay_TEST_9999");
        params.put("vnp_OrderInfo", "Topup wallet transaction " + txId);
        params.put("vnp_PayDate", "20261120010000");

        List<String> fieldNames = new ArrayList<>(params.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        for (String fieldName : fieldNames) {
            String val = params.get(fieldName);
            if (val != null && !val.isEmpty()) {
                String encodedVal = URLEncoder.encode(val, StandardCharsets.UTF_8);
                if (hashData.length() > 0) {
                    hashData.append('&');
                }
                hashData.append(fieldName).append('=').append(encodedVal);
            }
        }

        String secureHash = vnpayService.hmacSHA512(vnpayService.getHashSecret(), hashData.toString());
        params.put("vnp_SecureHash", secureHash);
        return params;
    }
}
