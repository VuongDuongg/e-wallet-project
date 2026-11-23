package com.meme.vwallet.integration;

import com.meme.vwallet.dto.request.TransferRequest;
import com.meme.vwallet.dto.response.TransferResponse;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.repository.LedgerEntryRepository;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.service.IdempotencyService;
import com.meme.vwallet.service.TransferService;
import com.meme.vwallet.service.UserService;
import com.meme.vwallet.support.SecurityTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest
class P2PTransferDeadlockTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserService userService;

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

    private final Map<String, Object> lockMap = new ConcurrentHashMap<>();

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        lockMap.clear();
        when(idempotencyService.formatTransferKey(any(), any()))
                .thenAnswer(inv -> "idemp:transfer:" + inv.getArgument(0) + ":" + inv.getArgument(1));
        when(idempotencyService.acquireLock(any()))
                .thenAnswer(inv -> lockMap.putIfAbsent(inv.getArgument(0), "LOCKED") == null);
        when(idempotencyService.getReceipt(any(), any()))
                .thenAnswer(inv -> {
                    Object val = lockMap.get(inv.getArgument(0));
                    if (val == null || "LOCKED".equals(val)) {
                        return Optional.empty();
                    }
                    return Optional.of(val);
                });
        doAnswer(inv -> {
            lockMap.remove(inv.getArgument(0));
            return null;
        }).when(idempotencyService).releaseLock(any());
        doAnswer(inv -> {
            lockMap.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(idempotencyService).saveReceipt(any(), any(), any());

        // Clean up previous transactions and ledger entries
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();

        // Prepare User A and User B
        userA = userRepository.findByPhoneNumber("0901234567")
                .orElseGet(() -> userRepository.save(User.builder()
                        .phoneNumber("0901234567")
                        .fullName("Nguyen Van A")
                        .passwordHash("$2a$10$uYvDISDqtjS1r0EEE3jdVOXaV7/9eiVflcEWiFyXpKrhoRKnK4tNW")
                        .status("ACTIVE")
                        .build()));

        userB = userRepository.findByPhoneNumber("0987654321")
                .orElseGet(() -> userRepository.save(User.builder()
                        .phoneNumber("0987654321")
                        .fullName("Tran Thi B")
                        .passwordHash("$2a$10$uYvDISDqtjS1r0EEE3jdVOXaV7/9eiVflcEWiFyXpKrhoRKnK4tNW")
                        .status("ACTIVE")
                        .build()));

        // Ensure both users have valid PIN "123456"
        userService.setPin(userA.getId(), "123456");
        userService.setPin(userB.getId(), "123456");

        // Prepare initial wallet balances: User A = 100,000 VND, User B = 100,000 VND
        Wallet walletA = walletRepository.findByUserId(userA.getId())
                .orElseGet(() -> Wallet.builder()
                        .user(userA)
                        .currency("VND")
                        .status(WalletStatus.ACTIVE)
                        .build());
        walletA.setBalance(100_000L);
        walletRepository.save(walletA);

        Wallet walletB = walletRepository.findByUserId(userB.getId())
                .orElseGet(() -> Wallet.builder()
                        .user(userB)
                        .currency("VND")
                        .status(WalletStatus.ACTIVE)
                        .build());
        walletB.setBalance(100_000L);
        walletRepository.save(walletB);

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
        if (userB != null) {
            walletRepository.findByUserId(userB.getId()).ifPresent(w -> {
                w.setBalance(500_000L);
                walletRepository.save(w);
            });
        }
    }

    @Test
    @DisplayName("20 concurrent bidirectional transfers execute with zero deadlocks, balance conservation, and 40 ledger entries")
    void testBidirectionalConcurrentTransfersDeadlockFreedom() throws Exception {
        int totalThreads = 20;
        int transfersPerDirection = 10;
        long transferAmount = 1_000L;

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);

        // 10 threads concurrently transfer from User A to User B (1,000 VND each)
        for (int i = 0; i < transfersPerDirection; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    SecurityTestUtils.setAuthenticatedUser(userA.getId(), userA.getPhoneNumber(), userA.getFullName());
                    TransferRequest request = TransferRequest.builder()
                            .receiverPhone(userB.getPhoneNumber())
                            .amount(transferAmount)
                            .pin("123456")
                            .description("A to B transfer #" + index)
                            .build();
                    TransferResponse response = transferService.transfer(
                            userA.getId(),
                            "A-TO-B-" + UUID.randomUUID(),
                            request
                    );
                    if (response != null && response.isSuccess()) {
                        successCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    SecurityTestUtils.clearSecurityContext();
                    doneLatch.countDown();
                }
            });
        }

        // 10 threads concurrently transfer from User B to User A (1,000 VND each)
        for (int i = 0; i < transfersPerDirection; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    SecurityTestUtils.setAuthenticatedUser(userB.getId(), userB.getPhoneNumber(), userB.getFullName());
                    TransferRequest request = TransferRequest.builder()
                            .receiverPhone(userA.getPhoneNumber())
                            .amount(transferAmount)
                            .pin("123456")
                            .description("B to A transfer #" + index)
                            .build();
                    TransferResponse response = transferService.transfer(
                            userB.getId(),
                            "B-TO-A-" + UUID.randomUUID(),
                            request
                    );
                    if (response != null && response.isSuccess()) {
                        successCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    SecurityTestUtils.clearSecurityContext();
                    doneLatch.countDown();
                }
            });
        }

        // Wait until all 20 threads are ready
        boolean allReady = readyLatch.await(10, TimeUnit.SECONDS);
        assertThat(allReady).as("All 20 threads must be ready").isTrue();

        // Release all 20 threads simultaneously
        startLatch.countDown();

        // Wait for all 20 threads to complete
        boolean allFinished = doneLatch.await(30, TimeUnit.SECONDS);
        assertThat(allFinished).as("All 20 threads must finish within timeout").isTrue();
        executor.shutdown();

        // Assert 1: No threads encounter DeadlockLoserDataAccessException or deadlock timeout
        assertThat(exceptions).noneMatch(t ->
                t instanceof DeadlockLoserDataAccessException ||
                (t.getCause() != null && t.getCause() instanceof DeadlockLoserDataAccessException)
        );
        assertThat(exceptions).as("No exceptions should be thrown during concurrent transfers").isEmpty();
        assertThat(successCount.get()).as("All 20 transfers must succeed").isEqualTo(totalThreads);

        // Assert 2: Total balance invariant holds: Balance_A + Balance_B == 200,000 VND
        Wallet finalWalletA = walletRepository.findByUserId(userA.getId()).orElseThrow();
        Wallet finalWalletB = walletRepository.findByUserId(userB.getId()).orElseThrow();
        long balanceA = finalWalletA.getBalance();
        long balanceB = finalWalletB.getBalance();

        assertThat(balanceA + balanceB)
                .as("Total balance invariant must hold: Balance_A + Balance_B == 200,000 VND")
                .isEqualTo(200_000L);
        assertThat(balanceA)
                .as("User A balance must equal 100,000 VND (10 debits and 10 credits of 1,000 VND)")
                .isEqualTo(100_000L);
        assertThat(balanceB)
                .as("User B balance must equal 100,000 VND (10 debits and 10 credits of 1,000 VND)")
                .isEqualTo(100_000L);

        // Assert 3: Exactly 40 ledger entries created (20 transactions * 2 entries each)
        long ledgerCount = ledgerEntryRepository.count();
        assertThat(ledgerCount)
                .as("Exactly 40 ledger entries must be created for 20 transfers")
                .isEqualTo(40L);

        long txCount = transactionRepository.count();
        assertThat(txCount)
                .as("Exactly 20 completed transactions must be saved")
                .isEqualTo(20L);
    }
}
