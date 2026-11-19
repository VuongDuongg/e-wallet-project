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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private ArgumentCaptor<LedgerEntry> ledgerEntryCaptor;

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
        @DisplayName("should debit sender, credit receiver, save wallets, and record 2 ledger entries")
        void executeDoubleEntry_success() {
            Long transferAmount = 200_000L;
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, transferAmount);

            // Verify wallet balances updated
            assertThat(senderWallet.getBalance()).isEqualTo(800_000L);
            assertThat(receiverWallet.getBalance()).isEqualTo(700_000L);

            // Verify wallets saved
            verify(walletRepository).save(senderWallet);
            verify(walletRepository).save(receiverWallet);

            // Verify exactly 2 ledger entries saved
            verify(ledgerEntryRepository, times(2)).save(ledgerEntryCaptor.capture());
            List<LedgerEntry> savedEntries = ledgerEntryCaptor.getAllValues();
            assertThat(savedEntries).hasSize(2);

            // First entry: DEBIT for sender
            LedgerEntry debitEntry = savedEntries.get(0);
            assertThat(debitEntry.getTransaction()).isEqualTo(sampleTransaction);
            assertThat(debitEntry.getWallet()).isEqualTo(senderWallet);
            assertThat(debitEntry.getEntryType()).isEqualTo(EntryType.DEBIT);
            assertThat(debitEntry.getAmount()).isEqualTo(transferAmount);
            assertThat(debitEntry.getBalanceAfter()).isEqualTo(800_000L);

            // Second entry: CREDIT for receiver
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
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, transferAmount);

            assertThat(senderWallet.getBalance()).isEqualTo(0L);
            assertThat(receiverWallet.getBalance()).isEqualTo(1_500_000L);

            verify(walletRepository).save(senderWallet);
            verify(walletRepository).save(receiverWallet);

            verify(ledgerEntryRepository, times(2)).save(ledgerEntryCaptor.capture());
            List<LedgerEntry> savedEntries = ledgerEntryCaptor.getAllValues();
            assertThat(savedEntries.get(0).getBalanceAfter()).isEqualTo(0L);
            assertThat(savedEntries.get(0).getEntryType()).isEqualTo(EntryType.DEBIT);
            assertThat(savedEntries.get(1).getBalanceAfter()).isEqualTo(1_500_000L);
            assertThat(savedEntries.get(1).getEntryType()).isEqualTo(EntryType.CREDIT);
        }

        @Test
        @DisplayName("should throw InsufficientBalanceException when sender balance is less than amount")
        void executeDoubleEntry_insufficientBalance_shouldThrowException() {
            senderWallet.setBalance(100_000L);
            Long transferAmount = 200_000L;

            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, transferAmount))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .hasMessageContaining("Số dư không đủ");

            // Balances must remain unchanged
            assertThat(senderWallet.getBalance()).isEqualTo(100_000L);
            assertThat(receiverWallet.getBalance()).isEqualTo(500_000L);

            // No persistence interactions
            verify(walletRepository, never()).save(any());
            verify(ledgerEntryRepository, never()).save(any());
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, -1L, -100_000L})
        @DisplayName("should throw IllegalArgumentException when amount <= 0")
        void executeDoubleEntry_invalidAmount_shouldThrowException(long invalidAmount) {
            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, invalidAmount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Số tiền giao dịch phải lớn hơn 0");

            assertThat(senderWallet.getBalance()).isEqualTo(1_000_000L);
            assertThat(receiverWallet.getBalance()).isEqualTo(500_000L);

            verify(walletRepository, never()).save(any());
            verify(ledgerEntryRepository, never()).save(any());
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("should throw IllegalArgumentException when amount is null")
        void executeDoubleEntry_nullAmount_shouldThrowException(Long nullAmount) {
            assertThatThrownBy(() -> ledgerService.executeDoubleEntry(sampleTransaction, senderWallet, receiverWallet, nullAmount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Số tiền giao dịch phải lớn hơn 0");

            assertThat(senderWallet.getBalance()).isEqualTo(1_000_000L);
            assertThat(receiverWallet.getBalance()).isEqualTo(500_000L);

            verify(walletRepository, never()).save(any());
            verify(ledgerEntryRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("recordEntry")
    class RecordEntryTests {

        @Test
        @DisplayName("should build, save, and return a single ledger entry")
        void recordEntry_success() {
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry result = ledgerService.recordEntry(sampleTransaction, senderWallet, EntryType.DEBIT, 50_000L, 950_000L);

            assertThat(result).isNotNull();
            assertThat(result.getTransaction()).isEqualTo(sampleTransaction);
            assertThat(result.getWallet()).isEqualTo(senderWallet);
            assertThat(result.getEntryType()).isEqualTo(EntryType.DEBIT);
            assertThat(result.getAmount()).isEqualTo(50_000L);
            assertThat(result.getBalanceAfter()).isEqualTo(950_000L);

            verify(ledgerEntryRepository).save(any(LedgerEntry.class));
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
        @DisplayName("getEntriesByWalletId should delegate to repository")
        void getEntriesByWalletId_success() {
            LedgerEntry entry1 = LedgerEntry.builder().id(1L).wallet(senderWallet).build();
            when(ledgerEntryRepository.findByWalletId(1L)).thenReturn(List.of(entry1));

            List<LedgerEntry> results = ledgerService.getEntriesByWalletId(1L);

            assertThat(results).containsExactly(entry1);
            verify(ledgerEntryRepository).findByWalletId(1L);
        }
    }
}
