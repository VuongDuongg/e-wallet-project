package com.meme.vwallet.service;

import com.meme.vwallet.entity.LedgerEntry;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.EntryType;
import com.meme.vwallet.exception.InsufficientBalanceException;
import com.meme.vwallet.repository.LedgerEntryRepository;
import com.meme.vwallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletRepository walletRepository;

    @Transactional
    public void executeDoubleEntry(Transaction tx, Wallet sender, Wallet receiver, Long amount) {
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("Số tiền giao dịch phải lớn hơn 0");
        }
        if (sender == null || sender.getBalance() == null || sender.getBalance() < amount) {
            throw new InsufficientBalanceException("Số dư không đủ để thực hiện giao dịch");
        }

        sender.setBalance(sender.getBalance() - amount);
        receiver.setBalance(receiver.getBalance() + amount);

        walletRepository.save(sender);
        walletRepository.save(receiver);

        recordEntry(tx, sender, EntryType.DEBIT, amount, sender.getBalance());
        recordEntry(tx, receiver, EntryType.CREDIT, amount, receiver.getBalance());
    }

    @Transactional
    public LedgerEntry recordEntry(Transaction tx, Wallet wallet, EntryType type, Long amount, Long balanceAfter) {
        LedgerEntry entry = LedgerEntry.builder()
                .transaction(tx)
                .wallet(wallet)
                .entryType(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .build();
        return ledgerEntryRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> getEntriesByTransactionId(Long transactionId) {
        return ledgerEntryRepository.findByTransactionId(transactionId);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> getEntriesByWalletId(Long walletId) {
        return ledgerEntryRepository.findByWalletId(walletId);
    }
}
