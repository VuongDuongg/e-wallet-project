package com.meme.vwallet.service;

import com.meme.vwallet.dto.request.TopupRequest;
import com.meme.vwallet.dto.response.TopupResponse;
import com.meme.vwallet.dto.response.TransactionResponse;
import com.meme.vwallet.dto.response.VnpayIpnResponse;
import com.meme.vwallet.dto.response.WalletResponse;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.event.TransactionCompletedEvent;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserService userService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private VnpayService vnpayService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PaymentService paymentService;

    private User testUser;
    private Wallet systemWallet;
    private Wallet userWallet;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(2L)
                .phoneNumber("0901234567")
                .fullName("Nguyen Van A")
                .build();

        systemWallet = Wallet.builder()
                .id(1L)
                .balance(1000000000000L)
                .currency("VND")
                .status(WalletStatus.ACTIVE)
                .build();

        userWallet = Wallet.builder()
                .id(2L)
                .user(testUser)
                .balance(1000000L)
                .currency("VND")
                .status(WalletStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("Phase 1: Initiate Top-up")
    class InitiateTopupTests {

        @Test
        @DisplayName("Test 1: Phase 1 creates PENDING top-up transaction with SYSTEM_WALLET as sender")
        void shouldCreatePendingTopupTransactionWithSystemWalletAsSender() {
            Long userId = 2L;
            TopupRequest request = TopupRequest.builder().amount(500000L).build();
            String clientIp = "127.0.0.1";

            when(walletRepository.findById(1L)).thenReturn(Optional.of(systemWallet));
            when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(userWallet));

            when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                tx.setId(2002L);
                return tx;
            });

            String mockPaymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=50000000&vnp_TxnRef=2002";
            when(vnpayService.createPaymentUrl(eq(2002L), eq(500000L), anyString(), eq(clientIp)))
                    .thenReturn(mockPaymentUrl);

            TopupResponse response = paymentService.initiateTopup(userId, request, clientIp);

            assertThat(response).isNotNull();
            assertThat(response.getTransactionId()).isEqualTo(2002L);
            assertThat(response.getPaymentUrl()).isEqualTo(mockPaymentUrl);

            ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());

            Transaction savedTx = txCaptor.getValue();
            assertThat(savedTx.getSenderWallet().getId()).isEqualTo(1L); // SYSTEM_WALLET
            assertThat(savedTx.getReceiverWallet().getId()).isEqualTo(2L); // userWallet
            assertThat(savedTx.getAmount()).isEqualTo(500000L);
            assertThat(savedTx.getType()).isEqualTo(TransactionType.TOPUP);
            assertThat(savedTx.getStatus()).isEqualTo(TransactionStatus.PENDING);
            assertThat(savedTx.getIdempotencyKey()).startsWith("TOPUP_");
        }

        @Test
        @DisplayName("Should throw WalletException when user wallet not found")
        void shouldThrowWhenUserWalletNotFound() {
            when(walletRepository.findById(1L)).thenReturn(Optional.of(systemWallet));
            when(walletRepository.findByUserId(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.initiateTopup(99L, TopupRequest.builder().amount(10000L).build(), "127.0.0.1"))
                    .isInstanceOf(WalletException.class)
                    .hasMessageContaining("Không tìm thấy ví của người dùng");
        }

        @Test
        @DisplayName("Should throw WalletException when user wallet is not active")
        void shouldThrowWhenUserWalletNotActive() {
            userWallet.setStatus(WalletStatus.LOCKED);
            when(walletRepository.findById(1L)).thenReturn(Optional.of(systemWallet));
            when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(userWallet));

            assertThatThrownBy(() -> paymentService.initiateTopup(2L, TopupRequest.builder().amount(10000L).build(), "127.0.0.1"))
                    .isInstanceOf(WalletException.class)
                    .hasMessageContaining("Ví người dùng không ở trạng thái hoạt động");
        }
    }

    @Nested
    @DisplayName("Phase 2: Process Webhook Callback (IPN)")
    class ProcessWebhookTests {

        @Test
        @DisplayName("Test 2: Webhook with invalid signature returns RspCode: 97")
        void shouldReturn97WhenSignatureIsInvalid() {
            Map<String, String> params = new HashMap<>();
            params.put("vnp_TxnRef", "2002");
            params.put("vnp_Amount", "50000000");
            params.put("vnp_SecureHash", "invalid_signature");

            when(vnpayService.verifySignature(params)).thenReturn(false);

            VnpayIpnResponse response = paymentService.processWebhook(params);

            assertThat(response).isNotNull();
            assertThat(response.getRspCode()).isEqualTo("97");
            assertThat(response.getMessage()).isEqualTo("Invalid Checksum");
            verify(transactionRepository, never()).findByIdForUpdate(anyLong());
        }

        @Test
        @DisplayName("Test 3: Webhook with unknown vnp_TxnRef returns RspCode: 01")
        void shouldReturn01WhenOrderNotFound() {
            Map<String, String> params = new HashMap<>();
            params.put("vnp_TxnRef", "9999");
            params.put("vnp_Amount", "50000000");

            when(vnpayService.verifySignature(params)).thenReturn(true);
            when(transactionRepository.findByIdForUpdate(9999L)).thenReturn(Optional.empty());

            VnpayIpnResponse response = paymentService.processWebhook(params);

            assertThat(response).isNotNull();
            assertThat(response.getRspCode()).isEqualTo("01");
            assertThat(response.getMessage()).isEqualTo("Order Not Found");
        }

        @Test
        @DisplayName("Test 4: Webhook with mismatched amount marks transaction FAILED and returns RspCode: 04")
        void shouldReturn04AndMarkFailedWhenAmountMismatched() {
            Transaction tx = Transaction.builder()
                    .id(2002L)
                    .senderWallet(systemWallet)
                    .receiverWallet(userWallet)
                    .amount(500000L) // 500,000 VND
                    .status(TransactionStatus.PENDING)
                    .type(TransactionType.TOPUP)
                    .build();

            Map<String, String> params = new HashMap<>();
            params.put("vnp_TxnRef", "2002");
            params.put("vnp_Amount", "10000000"); // 100,000 VND * 100 != 500,000

            when(vnpayService.verifySignature(params)).thenReturn(true);
            when(transactionRepository.findByIdForUpdate(2002L)).thenReturn(Optional.of(tx));

            VnpayIpnResponse response = paymentService.processWebhook(params);

            assertThat(response).isNotNull();
            assertThat(response.getRspCode()).isEqualTo("04");
            assertThat(response.getMessage()).isEqualTo("Invalid Amount");

            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
            verify(transactionRepository).save(tx);
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), anyLong());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Test 5: Replayed Webhook on already completed transaction returns RspCode: 00 idempotently")
        void shouldReturn00IdempotentlyWhenTransactionAlreadyCompleted() {
            Transaction tx = Transaction.builder()
                    .id(2002L)
                    .senderWallet(systemWallet)
                    .receiverWallet(userWallet)
                    .amount(500000L)
                    .status(TransactionStatus.COMPLETED)
                    .type(TransactionType.TOPUP)
                    .build();

            Map<String, String> params = new HashMap<>();
            params.put("vnp_TxnRef", "2002");
            params.put("vnp_Amount", "50000000");

            when(vnpayService.verifySignature(params)).thenReturn(true);
            when(transactionRepository.findByIdForUpdate(2002L)).thenReturn(Optional.of(tx));

            VnpayIpnResponse response = paymentService.processWebhook(params);

            assertThat(response).isNotNull();
            assertThat(response.getRspCode()).isEqualTo("00");
            assertThat(response.getMessage()).isEqualTo("Order already confirmed");

            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), anyLong());
            verify(walletRepository, never()).findByIdForUpdate(anyLong());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Test 6: Valid Webhook with vnp_ResponseCode=00 completes transaction, locks wallets, mutates balances via ledgerService")
        void shouldCompleteTransactionAndMutateBalancesOnSuccess() {
            Transaction tx = Transaction.builder()
                    .id(2002L)
                    .senderWallet(systemWallet)
                    .receiverWallet(userWallet)
                    .amount(500000L)
                    .status(TransactionStatus.PENDING)
                    .type(TransactionType.TOPUP)
                    .build();

            Map<String, String> params = new HashMap<>();
            params.put("vnp_TxnRef", "2002");
            params.put("vnp_Amount", "50000000");
            params.put("vnp_ResponseCode", "00");
            params.put("vnp_TransactionNo", "VNPay_12345678");

            when(vnpayService.verifySignature(params)).thenReturn(true);
            when(transactionRepository.findByIdForUpdate(2002L)).thenReturn(Optional.of(tx));
            when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(systemWallet));
            when(walletRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(userWallet));

            VnpayIpnResponse response = paymentService.processWebhook(params);

            assertThat(response).isNotNull();
            assertThat(response.getRspCode()).isEqualTo("00");
            assertThat(response.getMessage()).isEqualTo("Confirm Success");

            // Verify wallet locking in min/max order
            verify(walletRepository).findByIdForUpdate(1L);
            verify(walletRepository).findByIdForUpdate(2L);

            // Verify double-entry ledger mutation
            verify(ledgerService).executeDoubleEntry(tx, systemWallet, userWallet, 500000L);

            // Verify transaction state
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(tx.getExternalRef()).isEqualTo("VNPay_12345678");
            verify(transactionRepository).save(tx);

            // Verify TransactionCompletedEvent published
            ArgumentCaptor<TransactionCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionCompletedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            TransactionCompletedEvent event = eventCaptor.getValue();
            assertThat(event.getEventId()).isNotBlank();
            assertThat(event.getTransactionId()).isEqualTo(2002L);
            assertThat(event.getTransactionType()).isEqualTo(TransactionType.TOPUP);
            assertThat(event.getSenderUserId()).isEqualTo(1L);
            assertThat(event.getReceiverUserId()).isEqualTo(2L);
            assertThat(event.getReceiverPhone()).isEqualTo("0901234567");
            assertThat(event.getReceiverName()).isEqualTo("Nguyen Van A");
            assertThat(event.getAmount()).isEqualTo(500000L);
            assertThat(event.getOccurredAt()).isNotNull();
        }

        @Test
        @DisplayName("Webhook with vnp_ResponseCode != 00 marks transaction FAILED and returns RspCode: 00")
        void shouldMarkFailedWhenGatewayReportsFailure() {
            Transaction tx = Transaction.builder()
                    .id(2002L)
                    .senderWallet(systemWallet)
                    .receiverWallet(userWallet)
                    .amount(500000L)
                    .status(TransactionStatus.PENDING)
                    .type(TransactionType.TOPUP)
                    .build();

            Map<String, String> params = new HashMap<>();
            params.put("vnp_TxnRef", "2002");
            params.put("vnp_Amount", "50000000");
            params.put("vnp_ResponseCode", "24"); // User cancelled
            params.put("vnp_TransactionNo", "VNPay_FAILED");

            when(vnpayService.verifySignature(params)).thenReturn(true);
            when(transactionRepository.findByIdForUpdate(2002L)).thenReturn(Optional.of(tx));

            VnpayIpnResponse response = paymentService.processWebhook(params);

            assertThat(response).isNotNull();
            assertThat(response.getRspCode()).isEqualTo("00");

            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
            assertThat(tx.getExternalRef()).isEqualTo("VNPay_FAILED");
            verify(transactionRepository).save(tx);
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), anyLong());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Webhook with locked user wallet throws WalletException")
        void shouldThrowWhenUserWalletInactiveInWebhook() {
            userWallet.setStatus(WalletStatus.LOCKED);
            Transaction tx = Transaction.builder()
                    .id(2002L)
                    .senderWallet(systemWallet)
                    .receiverWallet(userWallet)
                    .amount(500000L)
                    .status(TransactionStatus.PENDING)
                    .type(TransactionType.TOPUP)
                    .build();

            Map<String, String> params = new HashMap<>();
            params.put("vnp_TxnRef", "2002");
            params.put("vnp_Amount", "50000000");
            params.put("vnp_ResponseCode", "00");

            when(vnpayService.verifySignature(params)).thenReturn(true);
            when(transactionRepository.findByIdForUpdate(2002L)).thenReturn(Optional.of(tx));
            when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(systemWallet));
            when(walletRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(userWallet));

            assertThatThrownBy(() -> paymentService.processWebhook(params))
                    .isInstanceOf(WalletException.class)
                    .hasMessageContaining("Ví người dùng không ở trạng thái hoạt động");
        }

        @Test
        @DisplayName("Webhook with fractional/truncated amount fails strict multiplication check")
        void shouldReturn04WhenAmountIsFractionalOrTruncated() {
            Transaction tx = Transaction.builder()
                    .id(2002L)
                    .senderWallet(systemWallet)
                    .receiverWallet(userWallet)
                    .amount(500000L)
                    .status(TransactionStatus.PENDING)
                    .type(TransactionType.TOPUP)
                    .build();

            Map<String, String> params = new HashMap<>();
            params.put("vnp_TxnRef", "2002");
            params.put("vnp_Amount", "50000099");

            when(vnpayService.verifySignature(params)).thenReturn(true);
            when(transactionRepository.findByIdForUpdate(2002L)).thenReturn(Optional.of(tx));

            VnpayIpnResponse response = paymentService.processWebhook(params);

            assertThat(response).isNotNull();
            assertThat(response.getRspCode()).isEqualTo("04");
            assertThat(response.getMessage()).isEqualTo("Invalid Amount");
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("Transaction Status and Wallet Balance Queries")
    class QueryTests {

        @Test
        @DisplayName("Should return TransactionResponse for existing transaction")
        void shouldReturnTransactionResponse() {
            Transaction tx = Transaction.builder()
                    .id(1001L)
                    .senderWallet(systemWallet)
                    .receiverWallet(userWallet)
                    .amount(200000L)
                    .type(TransactionType.TOPUP)
                    .status(TransactionStatus.COMPLETED)
                    .description("Nạp tiền")
                    .externalRef("VNPay_888")
                    .createdAt(Instant.now())
                    .build();

            when(transactionRepository.findById(1001L)).thenReturn(Optional.of(tx));

            TransactionResponse response = paymentService.getTransactionStatus(1001L);

            assertThat(response).isNotNull();
            assertThat(response.getTransactionId()).isEqualTo(1001L);
            assertThat(response.getSenderWalletId()).isEqualTo(1L);
            assertThat(response.getReceiverWalletId()).isEqualTo(2L);
            assertThat(response.getAmount()).isEqualTo(200000L);
            assertThat(response.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(response.getExternalRef()).isEqualTo("VNPay_888");
        }

        @Test
        @DisplayName("Should throw 404 when transaction not found")
        void shouldThrow404WhenTransactionNotFound() {
            when(transactionRepository.findById(9999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.getTransactionStatus(9999L))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("Should allow sender or receiver to view transaction status (IDOR check)")
        void shouldAllowParticipantToViewTransactionStatus() {
            User receiverUser = User.builder().id(3L).phoneNumber("0987654321").fullName("Tran Thi B").build();
            Wallet receiverWallet = Wallet.builder().id(3L).user(receiverUser).balance(500000L).currency("VND").status(WalletStatus.ACTIVE).build();
            Transaction tx = Transaction.builder()
                    .id(1002L)
                    .senderWallet(userWallet) // owner = 2L
                    .receiverWallet(receiverWallet) // owner = 3L
                    .amount(100000L)
                    .type(TransactionType.P2P_TRANSFER)
                    .status(TransactionStatus.COMPLETED)
                    .createdAt(Instant.now())
                    .build();

            when(transactionRepository.findById(1002L)).thenReturn(Optional.of(tx));

            assertThat(paymentService.getTransactionStatus(2L, 1002L).getId()).isEqualTo(1002L);
            assertThat(paymentService.getTransactionStatus(3L, 1002L).getId()).isEqualTo(1002L);
        }

        @Test
        @DisplayName("Should throw 403 Forbidden when third-party user queries transaction (IDOR defense)")
        void shouldThrow403WhenNonParticipantQueriesTransaction() {
            User receiverUser = User.builder().id(3L).phoneNumber("0987654321").fullName("Tran Thi B").build();
            Wallet receiverWallet = Wallet.builder().id(3L).user(receiverUser).balance(500000L).currency("VND").status(WalletStatus.ACTIVE).build();
            Transaction tx = Transaction.builder()
                    .id(1003L)
                    .senderWallet(userWallet) // owner = 2L
                    .receiverWallet(receiverWallet) // owner = 3L
                    .amount(100000L)
                    .type(TransactionType.P2P_TRANSFER)
                    .status(TransactionStatus.COMPLETED)
                    .createdAt(Instant.now())
                    .build();

            when(transactionRepository.findById(1003L)).thenReturn(Optional.of(tx));

            assertThatThrownBy(() -> paymentService.getTransactionStatus(99L, 1003L))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("Should return WalletResponse for existing user wallet")
        void shouldReturnWalletResponse() {
            when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(userWallet));

            WalletResponse response = paymentService.getWalletBalance(2L);

            assertThat(response).isNotNull();
            assertThat(response.getWalletId()).isEqualTo(2L);
            assertThat(response.getUserId()).isEqualTo(2L);
            assertThat(response.getBalance()).isEqualTo(1000000L);
            assertThat(response.getCurrency()).isEqualTo("VND");
            assertThat(response.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        }

        @Test
        @DisplayName("Should throw 404 when user wallet not found")
        void shouldThrow404WhenUserWalletNotFound() {
            when(walletRepository.findByUserId(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.getWalletBalance(99L))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }
}
