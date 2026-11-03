package com.moneytransfer.account.application;

import com.moneytransfer.account.domain.Account;
import com.moneytransfer.account.domain.AccountStatus;
import com.moneytransfer.account.domain.RuleViolationException;
import com.moneytransfer.account.persistence.AccountJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Service
public class AccountService {

    private final AccountJpaRepository accounts;

    public AccountService(AccountJpaRepository accounts) {
        this.accounts = accounts;
    }

    public Account open(String id, String holderName, String currency, BigDecimal initialBalance) {
        if (accounts.existsById(id)) {
            throw alreadyExists(id);
        }
        try {
            return accounts.saveAndFlush(Account.open(id, holderName, currency.toUpperCase(Locale.ROOT), initialBalance));
        } catch (DataIntegrityViolationException e) {
            throw alreadyExists(id); // created by a concurrent request a moment ago
        }
    }

    public Account get(String id) {
        return accounts.findById(id)
                .orElseThrow(() -> new RuleViolationException(404, "ACCOUNT_NOT_FOUND", "no account " + id));
    }

    public List<Account> list() {
        return accounts.findAllByOrderByIdAsc();
    }

    /**
     * Freeze, unfreeze or close. Saved with the account's @Version, so if money moved on the account
     * between the read and the save (e.g. a hold), the save fails instead of closing a non-empty
     * account on stale numbers.
     */
    @Transactional
    public Account changeStatus(String id, AccountStatus status) {
        Account account = get(id);
        account.changeStatus(status);
        return accounts.saveAndFlush(account);
    }

    private static RuleViolationException alreadyExists(String id) {
        return new RuleViolationException(409, "ACCOUNT_ALREADY_EXISTS", "account " + id + " already exists");
    }
}
