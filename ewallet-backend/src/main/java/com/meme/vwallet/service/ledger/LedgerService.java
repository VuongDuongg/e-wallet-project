package com.meme.vwallet.service.ledger;

import com.meme.vwallet.entity.LedgerEntry;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.EntryType;
import com.meme.vwallet.exception.InsufficientBalanceException;
import com.meme.vwallet.repository.LedgerEntryRepository;
import com.meme.vwallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletRepository walletRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void executeDoubleEntry(Transaction tx, Wallet sender, Wallet receiver, Long amount) {
        if (tx == null || tx.getId() == null) {
            throw new IllegalArgumentException("Giao dịch không hợp lệ");
        }
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("Số tiền giao dịch phải lớn hơn 0");
        }
        if (sender == null || sender.getId() == null || sender.getBalance() == null) {
            throw new IllegalArgumentException("Ví gửi tiền không hợp lệ");
        }
        if (receiver == null || receiver.getId() == null || receiver.getBalance() == null) {
            throw new IllegalArgumentException("Ví nhận tiền không hợp lệ");
        }
        if (sender.getId().equals(receiver.getId())) {
            throw new IllegalArgumentException("Không thể ghi sổ kép trên cùng một ví");
        }
        if (sender.getCurrency() == null || !Objects.equals(sender.getCurrency(), receiver.getCurrency())) {
            throw new IllegalArgumentException("Loại tiền tệ của hai ví không khớp");
        }
        if (sender.getBalance() < amount) {
            throw new InsufficientBalanceException("Số dư không đủ để thực hiện giao dịch");
        }

        // Compute both new balances before mutating either wallet so Long overflow aborts cleanly
        long newSenderBalance = Math.subtractExact(sender.getBalance(), amount);
        long newReceiverBalance = Math.addExact(receiver.getBalance(), amount);

        sender.setBalance(newSenderBalance);
        receiver.setBalance(newReceiverBalance);

        walletRepository.saveAll(List.of(sender, receiver));

        LedgerEntry debitEntry = buildEntry(tx, sender, EntryType.DEBIT, amount, newSenderBalance);
        LedgerEntry creditEntry = buildEntry(tx, receiver, EntryType.CREDIT, amount, newReceiverBalance);
        ledgerEntryRepository.saveAll(List.of(debitEntry, creditEntry));
    }

    private LedgerEntry buildEntry(Transaction tx, Wallet wallet, EntryType type, Long amount, Long balanceAfter) {
        return LedgerEntry.builder()
                .transaction(tx)
                .wallet(wallet)
                .entryType(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .build();
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> getEntriesByTransactionId(Long transactionId) {
        if (transactionId == null) {
            throw new IllegalArgumentException("transactionId không được để trống");
        }
        return ledgerEntryRepository.findByTransactionId(transactionId);
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntry> getEntriesByWalletId(Long walletId, Pageable pageable) {
        if (walletId == null) {
            throw new IllegalArgumentException("walletId không được để trống");
        }
        if (pageable == null) {
            throw new IllegalArgumentException("pageable không được để trống");
        }
        return ledgerEntryRepository.findByWalletIdOrderByCreatedAtDescIdDesc(walletId, pageable);
    }
}
