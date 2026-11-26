package com.meme.vwallet.service;

import com.meme.vwallet.dto.response.PageResponse;
import com.meme.vwallet.dto.response.TransactionHistoryItemResponse;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionQueryServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private WalletRepository walletRepository;

    @InjectMocks
    private TransactionQueryService transactionQueryService;

    private Wallet systemWallet;
    private Wallet userWallet;
    private Wallet peerWallet;

    @BeforeEach
    void setUp() {
        User systemUser = User.builder()
                .id(1L)
                .phoneNumber("0000000000")
                .fullName("SYSTEM_USER")
                .build();

        User userA = User.builder()
                .id(2L)
                .phoneNumber("0901234567")
                .fullName("Nguyen Van A")
                .build();

        User userB = User.builder()
                .id(3L)
                .phoneNumber("0987654321")
                .fullName("Tran Thi B")
                .build();

        systemWallet = Wallet.builder()
                .id(1L)
                .user(systemUser)
                .status(WalletStatus.ACTIVE)
                .build();

        userWallet = Wallet.builder()
                .id(2L)
                .user(userA)
                .status(WalletStatus.ACTIVE)
                .build();

        peerWallet = Wallet.builder()
                .id(3L)
                .user(userB)
                .status(WalletStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("getHistory computes IN/OUT direction and counterparties for P2P, TOPUP, and WITHDRAW")
    @SuppressWarnings("unchecked")
    void getHistory_allDirections_computesInOutAndCounterparties() {
        Instant now = Instant.now();

        Transaction p2pSent = Transaction.builder()
                .id(101L)
                .idempotencyKey("P2P_OUT_1")
                .senderWallet(userWallet)
                .receiverWallet(peerWallet)
                .amount(150_000L)
                .type(TransactionType.P2P_TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .description("Chuyen tien cho B")
                .createdAt(now)
                .build();

        Transaction p2pReceived = Transaction.builder()
                .id(102L)
                .idempotencyKey("P2P_IN_1")
                .senderWallet(peerWallet)
                .receiverWallet(userWallet)
                .amount(250_000L)
                .type(TransactionType.P2P_TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .description("Nhan tien tu B")
                .createdAt(now.minusSeconds(60))
                .build();

        Transaction topup = Transaction.builder()
                .id(103L)
                .idempotencyKey("TOPUP_1")
                .senderWallet(systemWallet)
                .receiverWallet(userWallet)
                .amount(500_000L)
                .type(TransactionType.TOPUP)
                .status(TransactionStatus.COMPLETED)
                .description("Nap tien VNPay")
                .externalRef("VNPAY_123")
                .createdAt(now.minusSeconds(120))
                .build();

        Transaction withdraw = Transaction.builder()
                .id(104L)
                .idempotencyKey("WD_1")
                .senderWallet(userWallet)
                .receiverWallet(systemWallet)
                .amount(100_000L)
                .type(TransactionType.WITHDRAW)
                .status(TransactionStatus.COMPLETED)
                .description("Rut tien ve Vietcombank")
                .externalRef("BANK_REF_456")
                .createdAt(now.minusSeconds(180))
                .build();

        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(userWallet));

        Page<Transaction> pageResult = new PageImpl<>(List.of(p2pSent, p2pReceived, topup, withdraw));
        when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(pageResult);

        PageResponse<TransactionHistoryItemResponse> response = transactionQueryService.getHistory(
                2L, 0, 20, null, null, "ALL", null, null
        );

        assertThat(response.getContent()).hasSize(4);

        TransactionHistoryItemResponse sentItem = response.getContent().get(0);
        assertThat(sentItem.getTransactionId()).isEqualTo(101L);
        assertThat(sentItem.getType()).isEqualTo(TransactionType.P2P_TRANSFER);
        assertThat(sentItem.getDirection()).isEqualTo("OUT");
        assertThat(sentItem.getAmount()).isEqualTo(150_000L);
        assertThat(sentItem.getCounterpartyPhone()).isEqualTo("0987654321");
        assertThat(sentItem.getCounterpartyName()).isEqualTo("Tran Thi B");

        TransactionHistoryItemResponse receivedItem = response.getContent().get(1);
        assertThat(receivedItem.getTransactionId()).isEqualTo(102L);
        assertThat(receivedItem.getType()).isEqualTo(TransactionType.P2P_TRANSFER);
        assertThat(receivedItem.getDirection()).isEqualTo("IN");
        assertThat(receivedItem.getAmount()).isEqualTo(250_000L);
        assertThat(receivedItem.getCounterpartyPhone()).isEqualTo("0987654321");
        assertThat(receivedItem.getCounterpartyName()).isEqualTo("Tran Thi B");

        TransactionHistoryItemResponse topupItem = response.getContent().get(2);
        assertThat(topupItem.getTransactionId()).isEqualTo(103L);
        assertThat(topupItem.getType()).isEqualTo(TransactionType.TOPUP);
        assertThat(topupItem.getDirection()).isEqualTo("IN");
        assertThat(topupItem.getCounterpartyPhone()).isEqualTo("SYSTEM");
        assertThat(topupItem.getCounterpartyName()).isEqualTo("VNPay Top-up");
        assertThat(topupItem.getExternalRef()).isEqualTo("VNPAY_123");

        TransactionHistoryItemResponse withdrawItem = response.getContent().get(3);
        assertThat(withdrawItem.getTransactionId()).isEqualTo(104L);
        assertThat(withdrawItem.getType()).isEqualTo(TransactionType.WITHDRAW);
        assertThat(withdrawItem.getDirection()).isEqualTo("OUT");
        assertThat(withdrawItem.getCounterpartyPhone()).isEqualTo("BANK");
        assertThat(withdrawItem.getCounterpartyName()).isEqualTo("Rút tiền ngân hàng");
        assertThat(withdrawItem.getExternalRef()).isEqualTo("BANK_REF_456");
    }

    @Test
    @DisplayName("getHistory throws IllegalArgumentException when from is after to")
    void getHistory_invalidDateRange_throwsIllegalArgument() {
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(userWallet));

        Instant from = Instant.parse("2026-11-26T12:00:00Z");
        Instant to = Instant.parse("2026-11-25T12:00:00Z");

        assertThatThrownBy(() -> transactionQueryService.getHistory(2L, 0, 20, null, null, "ALL", from, to))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getHistory throws IllegalArgumentException when direction is not ALL, IN, or OUT")
    void getHistory_invalidDirection_throwsIllegalArgument() {
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(userWallet));

        assertThatThrownBy(() -> transactionQueryService.getHistory(2L, 0, 20, null, null, "SIDEWAYS", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getHistory caps page size at 100 and clamps negative page and zero size")
    @SuppressWarnings("unchecked")
    void getHistory_capsPageSizeAt100() {
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(userWallet));
        when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        transactionQueryService.getHistory(2L, -3, 500, null, null, "in", null, null);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(any(Specification.class), pageableCaptor.capture());

        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isEqualTo(0);
        assertThat(captured.getPageSize()).isEqualTo(100);
        assertThat(captured.getSort().getOrderFor("createdAt")).isEqualTo(Sort.Order.desc("createdAt"));
        assertThat(captured.getSort().getOrderFor("id")).isEqualTo(Sort.Order.desc("id"));

        transactionQueryService.getHistory(2L, 1, 0, null, null, "ALL", null, null);
        verify(transactionRepository, org.mockito.Mockito.times(2))
                .findAll(any(Specification.class), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("getHistory throws 404 ResponseStatusException when wallet does not exist")
    void getHistory_missingWallet_throwsNotFound() {
        when(walletRepository.findByUserId(eq(999L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionQueryService.getHistory(999L, 0, 20, null, null, "ALL", null, null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
