package com.moneytransfer.account.persistence;

import com.moneytransfer.account.domain.Hold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HoldJpaRepository extends JpaRepository<Hold, String> {

    /**
     * SELECT ... FOR UPDATE: locks the hold row until the transaction ends, so a capture and a
     * release of the same hold arriving together run one after the other, never both.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hold h where h.transferId = :transferId")
    Optional<Hold> findForUpdate(@Param("transferId") String transferId);

    List<Hold> findBySourceAccountIdOrderByCreatedAtDesc(String sourceAccountId);
}
