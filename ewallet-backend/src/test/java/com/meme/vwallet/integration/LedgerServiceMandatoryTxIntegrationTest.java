package com.meme.vwallet.integration;

import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.service.LedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LedgerServiceMandatoryTxIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private WalletRepository walletRepository;

    @Test
    @DisplayName("executeDoubleEntry requires an active transaction (Propagation.MANDATORY)")
    void executeDoubleEntry_withoutTransaction_throwsIllegalTransactionStateException() {
        Wallet sender = walletRepository.findById(2L).orElseThrow();
        Wallet receiver = walletRepository.findById(3L).orElseThrow();
        Transaction tx = Transaction.builder()
                .id(1L)
                .senderWallet(sender)
                .receiverWallet(receiver)
                .amount(10_000L)
                .build();

        assertThatThrownBy(() -> ledgerService.executeDoubleEntry(tx, sender, receiver, 10_000L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
