package com.moneytransfer.limits.persistence;

import com.moneytransfer.limits.domain.AccountLimit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountLimitJpaRepository extends JpaRepository<AccountLimit, String> {
}
