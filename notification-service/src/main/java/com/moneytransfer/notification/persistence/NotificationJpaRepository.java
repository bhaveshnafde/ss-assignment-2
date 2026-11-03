package com.moneytransfer.notification.persistence;

import com.moneytransfer.notification.domain.Notification;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationJpaRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByTransferIdAndTemplate(String transferId, String template);

    /**
     * SELECT ... FOR UPDATE: identical requests arriving together queue here, so only the first one
     * calls the provider; the others then see SENT and send nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.id = :id")
    Optional<Notification> findForUpdate(@Param("id") Long id);

    List<Notification> findByTransferIdOrderByIdAsc(String transferId);

    List<Notification> findByAccountIdOrderByIdDesc(String accountId, Pageable page);
}
