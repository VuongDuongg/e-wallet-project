package com.meme.vwallet.repository;

import com.meme.vwallet.entity.LedgerEntry;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.EntryType;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.entity.enums.WalletStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WalletRepositoryTest {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("Verify seed data loaded successfully from data.sql")
    void testSeedDataLoaded() {
        Optional<User> systemUser = userRepository.findByPhoneNumber("0000000000");
        assertThat(systemUser).isPresent();
        assertThat(systemUser.get().getFullName()).isEqualTo("SYSTEM_USER");

        Optional<Wallet> systemWallet = walletRepository.findByUserId(systemUser.get().getId());
        assertThat(systemWallet).isPresent();
        assertThat(systemWallet.get().getBalance()).isEqualTo(1_000_000_000_000L);
        assertThat(systemWallet.get().getStatus()).isEqualTo(WalletStatus.ACTIVE);

        Optional<User> userA = userRepository.findByPhoneNumber("0901234567");
        assertThat(userA).isPresent();
        assertThat(userA.get().getFullName()).isEqualTo("Nguyen Van A");

        Optional<Wallet> walletA = walletRepository.findByUserId(userA.get().getId());
        assertThat(walletA).isPresent();
        assertThat(walletA.get().getBalance()).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("Test findByIdForUpdate acquires pessimistic lock on wallet")
    void testFindByIdForUpdate() {
        Optional<Wallet> walletOpt = walletRepository.findByIdForUpdate(1L);
        assertThat(walletOpt).isPresent();
        assertThat(walletOpt.get().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Test save wallet and query by userId")
    void testSaveAndFindByUserId() {
        User newUser = User.builder()
                .phoneNumber("0912345678")
                .fullName("Le Van C")
                .passwordHash("$2a$10$dummyHash")
                .pinHash("$2a$10$dummyHash")
                .status("ACTIVE")
                .build();
        userRepository.save(newUser);

        Wallet newWallet = Wallet.builder()
                .user(newUser)
                .balance(250_000L)
                .currency("VND")
                .status(WalletStatus.ACTIVE)
                .build();
        walletRepository.save(newWallet);
        entityManager.flush();

        Optional<Wallet> retrieved = walletRepository.findByUserId(newUser.getId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getBalance()).isEqualTo(250_000L);
        assertThat(retrieved.get().getUser().getPhoneNumber()).isEqualTo("0912345678");
    }

    @Test
    @DisplayName("Test transaction repository findByIdempotencyKey and findByIdForUpdate")
    void testTransactionRepository() {
        Wallet sender = walletRepository.findById(1L).orElseThrow();
        Wallet receiver = walletRepository.findById(2L).orElseThrow();

        Transaction tx = Transaction.builder()
                .idempotencyKey("TEST_IDEMP_KEY_001")
                .senderWallet(sender)
                .receiverWallet(receiver)
                .amount(100_000L)
                .type(TransactionType.P2P_TRANSFER)
                .status(TransactionStatus.PENDING)
                .description("Test P2P transfer")
                .build();
        transactionRepository.save(tx);
        entityManager.flush();

        Optional<Transaction> byKey = transactionRepository.findByIdempotencyKey("TEST_IDEMP_KEY_001");
        assertThat(byKey).isPresent();
        assertThat(byKey.get().getAmount()).isEqualTo(100_000L);
        assertThat(byKey.get().getType()).isEqualTo(TransactionType.P2P_TRANSFER);

        Optional<Transaction> forUpdate = transactionRepository.findByIdForUpdate(tx.getId());
        assertThat(forUpdate).isPresent();
        assertThat(forUpdate.get().getIdempotencyKey()).isEqualTo("TEST_IDEMP_KEY_001");
    }

    @Test
    @DisplayName("Test ledger entry repository findByTransactionId")
    void testLedgerEntryRepository() {
        Wallet sender = walletRepository.findById(1L).orElseThrow();
        Wallet receiver = walletRepository.findById(2L).orElseThrow();

        Transaction tx = Transaction.builder()
                .idempotencyKey("TEST_IDEMP_KEY_002")
                .senderWallet(sender)
                .receiverWallet(receiver)
                .amount(50_000L)
                .type(TransactionType.TOPUP)
                .status(TransactionStatus.COMPLETED)
                .build();
        transactionRepository.save(tx);

        LedgerEntry debitEntry = LedgerEntry.builder()
                .transaction(tx)
                .wallet(sender)
                .entryType(EntryType.DEBIT)
                .amount(50_000L)
                .balanceAfter(sender.getBalance() - 50_000L)
                .build();

        LedgerEntry creditEntry = LedgerEntry.builder()
                .transaction(tx)
                .wallet(receiver)
                .entryType(EntryType.CREDIT)
                .amount(50_000L)
                .balanceAfter(receiver.getBalance() + 50_000L)
                .build();

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);
        entityManager.flush();

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(tx.getId());
        assertThat(entries).hasSize(2);

        Optional<Long> sysWalletId = walletRepository.findIdByUserId(1L);
        assertThat(sysWalletId).contains(1L);

        Page<LedgerEntry> page = ledgerEntryRepository.findByWalletIdOrderByCreatedAtDescIdDesc(
                sender.getId(), PageRequest.of(0, 10));
        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getContent().get(0).getWalletId()).isEqualTo(sender.getId());
    }

    @Test
    @DisplayName("Test check constraint prevents negative wallet balance")
    void testNegativeBalanceCheckConstraint() {
        User user = User.builder()
                .phoneNumber("0999888777")
                .fullName("Test Negative")
                .passwordHash("$2a$10$dummyHash")
                .build();
        userRepository.save(user);

        Wallet wallet = Wallet.builder()
                .user(user)
                .balance(-100L)
                .currency("VND")
                .status(WalletStatus.ACTIVE)
                .build();

        assertThrows(DataIntegrityViolationException.class, () -> {
            walletRepository.save(wallet);
            entityManager.flush();
        });
    }
}
