/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sagas.staterepository;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.stereotype.Component;
import sagas.shared.RentalPaymentApi.ApproveRequest;
import sagas.shared.RentalPaymentApi.BikeRequested;
import sagas.shared.RentalPaymentApi.PaymentConfirmed;
import sagas.shared.RentalPaymentApi.PreparePayment;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static sagas.shared.RentalPaymentApi.PRICE;
import static sagas.shared.RentalPaymentApi.paymentReferenceFor;
import static sagas.shared.RentalPaymentApi.rentalIdFor;

/**
 * Keeping the process state in a table of your own: the most familiar shape for anyone coming from Axon Framework 4.
 */
// tag::process[]
@Component
public class RentalPaymentProcess {

    private final RentalPaymentProcessRepository repository;

    public RentalPaymentProcess(RentalPaymentProcessRepository repository) {
        this.repository = repository;
    }

    @EventHandler
    public CompletableFuture<?> on(BikeRequested event, CommandDispatcher dispatcher, ProcessingContext context) {
        if (repository.findById(event.rentalId()).isPresent()) {
            return CompletableFuture.completedFuture(null); // <1>
        }
        return dispatcher.send(new PreparePayment(paymentReferenceFor(event.rentalId()), PRICE))
                         .getResultMessage() // <2>
                         .thenRun(() -> context.runOnPrepareCommit(ignored -> repository.save( // <3>
                                 new RentalPaymentProcessState(event.rentalId(), event.bikeId(), event.renter())
                         )));
    }

    @EventHandler
    public CompletableFuture<?> on(PaymentConfirmed event, CommandDispatcher dispatcher, ProcessingContext context) {
        Optional<RentalPaymentProcessState> state =
                repository.findById(rentalIdFor(event.paymentReference()));
        if (state.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        RentalPaymentProcessState process = state.get();
        return dispatcher.send(new ApproveRequest(process.bikeId(), process.renter()))
                         .getResultMessage()
                         .thenRun(() -> context.runOnPrepareCommit(
                                 ignored -> repository.deleteById(process.rentalId()) // <4>
                         ));
    }
}
// end::process[]

// tag::state[]
@Entity
class RentalPaymentProcessState {

    @Id
    private String rentalId;
    private String bikeId;
    private String renter;

    protected RentalPaymentProcessState() {
    }

    RentalPaymentProcessState(String rentalId, String bikeId, String renter) {
        this.rentalId = rentalId;
        this.bikeId = bikeId;
        this.renter = renter;
    }

    String rentalId() {
        return rentalId;
    }

    String bikeId() {
        return bikeId;
    }

    String renter() {
        return renter;
    }
}
// end::state[]
