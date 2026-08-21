package org.axonframework.examples.sagarecipes.saga.repository;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;

@Entity
@Table(name = "payment_saga_state")
public class PaymentSagaState {
    @Id
    private String rentalId;
    private String bikeId;
    private String renter;
    private boolean paymentRequested;

    protected PaymentSagaState() {
    }

    private PaymentSagaState(RentalId rentalId, BikeId bikeId, String renter) {
        this.rentalId = rentalId.raw();
        this.bikeId = bikeId.raw();
        this.renter = renter;
        this.paymentRequested = true;
    }

    public static PaymentSagaState paymentRequested(RentalId rentalId, BikeId bikeId, String renter) {
        return new PaymentSagaState(rentalId, bikeId, renter);
    }

    public RentalId rentalId() {
        return RentalId.of(rentalId);
    }

    public BikeId bikeId() {
        return BikeId.of(bikeId);
    }

    public String renter() {
        return renter;
    }

    public boolean paymentRequested() {
        return paymentRequested;
    }
}
