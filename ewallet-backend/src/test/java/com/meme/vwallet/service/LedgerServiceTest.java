package com.meme.vwallet.service;

import com.meme.vwallet.entity.LedgerEntry;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.EntryType;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.exception.InsufficientBalanceException;
import com.meme.vwallet.repository.LedgerEntryRepository;
import com.meme.vwallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private WalletRepository walletRepository;

    @InjectMocks
    private LedgerService ledgerService;

    @Captor
    private ArgumentCaptor<Iterable<Wallet>> walletsCaptor;

    @Captor
    private ArgumentCaptor<Iterable<LedgerEntry>> entriesCaptor;

    private Wallet senderWallet;
    private Wallet receiverWallet;
    private Transaction sampleTransaction;

    @BeforeEach
    void setUp() {
        senderWallet = Wallet.builder()
                .id(1L)
                .balance(1_000_000L)
                .currency("VND")
                .status(WalletStatus.ACTIVE)
                .build();

        receiverWallet = Wallet.builder()
                .id(2L)
                .balance(500_000L)
                .currency("VND")
                .status(WalletStatus.ACTIVE)
                .build();

        sampleTransaction = Transaction.builder()
                .id(100L)
                .idempotencyKey("test-idemp-key-100")
                .senderWallet(senderWallet)
                .receiverWallet(receiverWallet)
                .amount(200_000L)
                .type(TransactionType.P2P_TRANSFER)
                .status(TransactionStatus.PENDING)
                .description("Chuyen tien an trua")
                .build();
    }

    @Nested
    @DisplayName("executeDoubleEntry")
    class ExecuteDoubleEntryTests {

        @Test
        @DisplayName("should debit sender, credit receiver, and persist via saveAll with 2 ledger entries")
        void executeDoubleEntry_success() {
            Long transferAmount = 200_000L;
            when(ledgerEntryRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, transferAmount);

            assertThat(senderWallet.getBalance()).isEqualTo(800_000L);
            assertThat(receiverWallet.getBalance()).isEqualTo(700_000L);

            verify(walletRepository).saveAll(walletsCaptor.capture());
            List<Wallet> savedWallets = new ArrayList<>();
            walletsCaptor.getValue().forEach(savedWallets::add);
            assertThat(savedWallets).containsExactly(senderWallet, receiverWallet);

            verify(ledgerEntryRepository).saveAll(entriesCaptor.capture());
            List<LedgerEntry> savedEntries = new ArrayList<>();
            entriesCaptor.getValue().forEach(savedEntries::add);
            assertThat(savedEntries).hasSize(2);

            LedgerEntry debitEntry = savedEntries.get(0);
            assertThat(debitEntry.getTransaction()).isEqualTo(sampleTransaction);
            assertThat(debitEntry.getWallet()).isEqualTo(senderWallet);
            assertThat(debitEntry.getEntryType()).isEqualTo(EntryType.DEBIT);
            assertThat(debitEntry.getAmount()).isEqualTo(transferAmount);
            assertThat(debitEntry.getBalanceAfter()).isEqualTo(800_000L);

            LedgerEntry creditEntry = savedEntries.get(1);
            assertThat(creditEntry.getTransaction()).isEqualTo(sampleTransaction);
            assertThat(creditEntry.getWallet()).isEqualTo(receiverWallet);
            assertThat(creditEntry.getEntryType()).isEqualTo(EntryType.CREDIT);
            assertThat(creditEntry.getAmount()).isEqualTo(transferAmount);
            assertThat(creditEntry.getBalanceAfter()).isEqualTo(700_000L);
        }

        @Test
        @DisplayName("should succeed when sender balance exactly equals transfer amount")
        void executeDoubleEntry_exactBalance_success() {
            Long transferAmount = 1_000_000L;
            when(ledgerEntryRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, transferAmount);

            assertThat(senderWallet.getBalance()).isEqualTo(0L);
            assertThat(receiverWallet.getBalance()).isEqualTo(1_500_000L);

            verify(walletRepository).saveAll(anyList());
            verify(ledgerEntryRepository).saveAll(entriesCaptor.capture());
            List<LedgerEntry> savedEntries = new ArrayList<>();
            entriesCaptor.getValue().forEach(savedEntries::add);
            assertThat(savedEntries.get(0).getBalanceAfter()).isEqualTo(0L);
            assertThat(savedEntries.get(0).getEntryType()).isEqualTo(EntryType.DEBIT);
            assertThat(savedEntries.get(1).getBalanceAfter()).isEqualTo(1_500_000L);
            assertThat(savedEntries.get(1).getEntryType()).isEqualTo(EntryType.CREDIT);
        }

        @Test
        @DisplayName("should throw InsufficientBalanceException when sender balance is less than amount")
        void executeDoubleEntry_insufficientBalance_shouldThrowException() {
            senderWallet.setBalance(100_000L);

            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, 200_000L))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .hasMessageContaining("Số dư không đủ");

            assertThat(senderWallet.getBalance()).isEqualTo(100_000L);
            assertThat(receiverWallet.getBalance()).isEqualTo(500_000L);

            verify(walletRepository, never()).saveAll(any());
            verify(ledgerEntryRepository, never()).saveAll(any());
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, -1L, -100_000L})
        @DisplayName("should throw IllegalArgumentException when amount <= 0")
        void executeDoubleEntry_invalidAmount_shouldThrowException(long invalidAmount) {
            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, invalidAmount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Số tiền giao dịch phải lớn hơn 0");

            verify(walletRepository, never()).saveAll(any());
            verify(ledgerEntryRepository, never()).saveAll(any());
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("should throw IllegalArgumentException when amount is null")
        void executeDoubleEntry_nullAmount_shouldThrowException(Long nullAmount) {
            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, nullAmount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Số tiền giao dịch phải lớn hơn 0");

            verify(walletRepository, never()).saveAll(any());
            verify(ledgerEntryRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when tx is null or tx.id is null")
        void executeDoubleEntry_invalidTransaction_shouldThrowException() {
            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(null, senderWallet, receiverWallet, 200_000L))
                    .isInstanceOf(IllegalArgumentException.class);

            Transaction unsavedTx = Transaction.builder().amount(200_000L).build();
            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(unsavedTx, senderWallet, receiverWallet, 200_000L))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(walletRepository, never()).saveAll(any());
            verify(ledgerEntryRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when sender or receiver is invalid")
        void executeDoubleEntry_invalidSenderOrReceiver_shouldThrowException() {
            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, null, receiverWallet, 200_000L))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, null, 200_000L))
                    .isInstanceOf(IllegalArgumentException.class);

            senderWallet.setId(null);
            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, 200_000L))
                    .isInstanceOf(IllegalArgumentException.class);
            senderWallet.setId(1L);

            receiverWallet.setBalance(null);
            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, 200_000L))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(walletRepository, never()).saveAll(any());
            verify(ledgerEntryRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when sender and receiver are the same wallet")
        void executeDoubleEntry_sameWallet_shouldThrowException() {
            receiverWallet.setId(1L);

            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, 200_000L))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(senderWallet.getBalance()).isEqualTo(1_000_000L);
            verify(walletRepository, never()).saveAll(any());
            verify(ledgerEntryRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when sender and receiver currencies mismatch")
        void executeDoubleEntry_currencyMismatch_shouldThrowException() {
            receiverWallet.setCurrency("USD");

            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, 200_000L))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(senderWallet.getBalance()).isEqualTo(1_000_000L);
            assertThat(receiverWallet.getBalance()).isEqualTo(500_000L);
            verify(walletRepository, never()).saveAll(any());
            verify(ledgerEntryRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("should throw ArithmeticException on Long balance overflow without mutating sender balance")
        void executeDoubleEntry_longOverflow_throwsArithmeticExceptionAndLeavesBalancesIntact() {
            receiverWallet.setBalance(Long.MAX_VALUE - 50_000L);

            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, 200_000L))
                    .isInstanceOf(ArithmeticException.class);

            assertThat(senderWallet.getBalance()).isEqualTo(1_000_000L);
            assertThat(receiverWallet.getBalance()).isEqualTo(Long.MAX_VALUE - 50_000L);
            verify(walletRepository, never()).saveAll(any());
            verify(ledgerEntryRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("query methods")
    class QueryMethodTests {

        @Test
        @DisplayName("getEntriesByTransactionId should delegate to repository")
        void getEntriesByTransactionId_success() {
            LedgerEntry entry1 = LedgerEntry.builder().id(1L).transaction(sampleTransaction).build();
            LedgerEntry entry2 = LedgerEntry.builder().id(2L).transaction(sampleTransaction).build();
            when(ledgerEntryRepository.findByTransactionId(100L)).thenReturn(List.of(entry1, entry2));

            List<LedgerEntry> results = ledgerService.getEntriesByTransactionId(100L);

            assertThat(results).containsExactly(entry1, entry2);
            verify(ledgerEntryRepository).findByTransactionId(100L);
        }

        @Test
        @DisplayName("getEntriesByWalletId should delegate paginated query to repository")
        void getEntriesByWalletId_paginated_success() {
            Pageable pageable = PageRequest.of(0, 20);
            LedgerEntry entry1 = LedgerEntry.builder().id(1L).wallet(senderWallet).build();
            Page<LedgerEntry> expectedPage = new PageImpl<>(List.of(entry1), pageable, 1);

            when(ledgerEntryRepository.findByWalletIdOrderByCreatedAtDescIdDesc(1L, pageable))
                    .thenReturn(expectedPage);

            Page<LedgerEntry> results = ledgerService.getEntriesByWalletId(1L, pageable);

            assertThat(results.getContent()).containsExactly(entry1);
            verify(ledgerEntryRepository).findByWalletIdOrderByCreatedAtDescIdDesc(1L, pageable);
        }
    }
}
