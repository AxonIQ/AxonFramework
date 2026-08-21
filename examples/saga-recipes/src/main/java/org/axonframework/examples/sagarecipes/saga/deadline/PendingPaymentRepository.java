package org.axonframework.examples.sagarecipes.saga.deadline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PendingPaymentRepository extends JpaRepository<PendingPayment, String> {
    List<PendingPayment> findByPreparedAtBefore(Instant cutoff);
}
