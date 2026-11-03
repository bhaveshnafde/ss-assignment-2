package com.moneytransfer.account.persistence;

import com.moneytransfer.account.domain.Account;
import com.moneytransfer.account.domain.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Every change to money is ONE conditional UPDATE statement. The database checks the condition and
 * changes the row in a single step while holding the row lock, so concurrent requests are applied
 * one after another and the condition is re-checked for each. Each method returns the number of
 * rows changed: 0 means the condition was false (e.g. not enough available money).
 */
public interface AccountJpaRepository extends JpaRepository<Account, String> {

    List<Account> findAllByOrderByIdAsc();

    /**
     * Challenge 1 / BR2: reserve money only if enough is available, in one statement.
     * Two requests for the last 100 cannot both succeed: the second one waits for the first's row
     * lock, then re-checks "available >= amount" against the new value and changes 0 rows.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Account a
               set a.available = a.available - :amount, a.version = a.version + 1, a.updatedAt = :now
             where a.id = :id and a.available >= :amount and a.status = :active
            """)
    int reserveIfAvailable(@Param("id") String id, @Param("amount") BigDecimal amount,
                           @Param("now") Instant now, @Param("active") AccountStatus active);

    /** Undo a reservation (hold released). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Account a
               set a.available = a.available + :amount, a.version = a.version + 1, a.updatedAt = :now
             where a.id = :id
            """)
    int unreserve(@Param("id") String id, @Param("amount") BigDecimal amount, @Param("now") Instant now);

    /** Capture, source side: the money already left "available" when it was held; now it leaves "balance". */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Account a
               set a.balance = a.balance - :amount, a.version = a.version + 1, a.updatedAt = :now
             where a.id = :id and a.balance >= :amount
            """)
    int debitReserved(@Param("id") String id, @Param("amount") BigDecimal amount, @Param("now") Instant now);

    /** Capture, destination side: the money arrives and can be spent at once. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Account a
               set a.balance = a.balance + :amount, a.available = a.available + :amount,
                   a.version = a.version + 1, a.updatedAt = :now
             where a.id = :id
            """)
    int credit(@Param("id") String id, @Param("amount") BigDecimal amount, @Param("now") Instant now);
}
