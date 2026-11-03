package com.moneytransfer.notification.persistence;

import com.moneytransfer.notification.domain.NotificationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface NotificationAttemptJpaRepository extends JpaRepository<NotificationAttempt, Long> {

    List<NotificationAttempt> findByNotificationIdInOrderByNotificationIdAscAttemptNoAsc(Collection<Long> notificationIds);
}
