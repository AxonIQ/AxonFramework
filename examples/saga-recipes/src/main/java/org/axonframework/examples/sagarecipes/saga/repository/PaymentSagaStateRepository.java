package org.axonframework.examples.sagarecipes.saga.repository;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentSagaStateRepository extends JpaRepository<PaymentSagaState, String> {
}
