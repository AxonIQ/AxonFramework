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

package org.axonframework.examples.sagarecipes.saga.repository;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;

/**
 * What the process remembers about one rental.
 * <p>
 * This is the nearest thing in the module to an Axon Framework 4 {@code SagaEntry}, with one difference that matters:
 * the schema is yours. Version 4 serialized the whole saga instance into an opaque blob, so the only way to ask a
 * question of it was to deserialize it. Here the columns are ordinary columns and can be queried, indexed and
 * reported on.
 * <p>
 * The fields worth noticing are {@code bikeId} and {@code renter}. They are the reason this process needs storage at
 * all: no entity in either context is keyed by a rental, so nothing else can tell the process which bike to approve
 * once payment arrives.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Entity
@Table(name = "saga_recipe_rental_payment_process")
public class PaymentSagaState {

    @Id
    private String rentalId;
    private String bikeId;
    private String renter;
    private boolean paymentRequested;
    private boolean paymentSettled;
    private boolean requestSettled;

    /**
     * Required by JPA.
     */
    protected PaymentSagaState() {
    }

    private PaymentSagaState(RentalId rentalId, BikeId bikeId, String renter) {
        this.rentalId = rentalId.raw();
        this.bikeId = bikeId.raw();
        this.renter = renter;
    }

    /**
     * Records that payment has been asked for.
     *
     * @param rentalId the rental being paid for
     * @param bikeId   the bike it concerns
     * @param renter   who is renting
     * @return the process state to store
     */
    static PaymentSagaState paymentRequested(RentalId rentalId, BikeId bikeId, String renter) {
        var state = new PaymentSagaState(rentalId, bikeId, renter);
        state.paymentRequested = true;
        return state;
    }

    RentalId rentalId() {
        return RentalId.of(rentalId);
    }

    BikeId bikeId() {
        return BikeId.of(bikeId);
    }

    String renter() {
        return renter;
    }

    boolean paymentRequested() {
        return paymentRequested;
    }

    boolean paymentSettled() {
        return paymentSettled;
    }

    boolean requestSettled() {
        return requestSettled;
    }

    void markPaymentSettled() {
        this.paymentSettled = true;
    }

    void markRequestSettled() {
        this.requestSettled = true;
    }
}
