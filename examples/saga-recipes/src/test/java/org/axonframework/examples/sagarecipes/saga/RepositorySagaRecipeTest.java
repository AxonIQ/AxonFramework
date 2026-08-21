package org.axonframework.examples.sagarecipes.saga;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.saga.repository.PaymentSagaStateRepository;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@AxonSpringBootTest(properties = "saga.recipe=repository")
class RepositorySagaRecipeTest extends SagaRecipeContractTest {
    @Autowired PaymentSagaStateRepository repository;

    @Test
    void givenBikeRequested_thenProgressRowRecordsDataNeededForSettlement() {
        RentalId rentalId = RentalId.random();
        BikeId bikeId = BikeId.random();

        fixture.given().event(new BikeRequested(bikeId, "renter", rentalId))
               .then().await(result -> result.expect(ignored -> {
                   var state = repository.findById(rentalId.raw()).orElseThrow();
                   assertThat(state.bikeId()).isEqualTo(bikeId);
                   assertThat(state.renter()).isEqualTo("renter");
                   assertThat(state.paymentRequested()).isTrue();
               }));
    }

    @Override
    protected void assertNoProgressRecorded(RentalId rentalId, java.util.List<Object> recordedPayloads) {
        assertThat(repository.findById(rentalId.raw())).isEmpty();
    }
}
