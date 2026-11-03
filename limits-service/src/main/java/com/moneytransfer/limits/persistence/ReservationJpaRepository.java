package com.moneytransfer.limits.persistence;

import com.moneytransfer.limits.domain.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ReservationJpaRepository extends JpaRepository<Reservation, String> {

    /** SELECT ... FOR UPDATE, so two releases of the same reservation run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.transferId = :transferId")
    Optional<Reservation> findForUpdate(@Param("transferId") String transferId);

    /** F1: how many transfers the account started within the velocity window. */
    long countByAccountIdAndCreatedAtAfter(String accountId, Instant after);
}
