package org.axonframework.examples.sagarecipes.saga;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentRequested;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@AxonSpringBootTest(properties = "saga.recipe=eventsourced")
class EventSourcedSagaRecipeTest extends SagaRecipeContractTest {
    @Test
    void givenBikeRequested_thenProcessRecordsPaymentRequestedEvent() {
        RentalId rentalId = RentalId.random();

        fixture.given().event(new BikeRequested(BikeId.random(), "renter", rentalId))
               .then().await(result -> result.eventsSatisfy(events ->
                       assertThat(events.stream().map(event -> event.payload()))
                               .anyMatch(event -> event instanceof RentalPaymentRequested requested
                                       && requested.rentalId().equals(rentalId))));
    }

    @Override
    protected void assertNoProgressRecorded(RentalId rentalId, java.util.List<Object> recordedPayloads) {
        assertThat(recordedPayloads)
                .noneMatch(event -> event instanceof RentalPaymentRequested requested
                        && requested.rentalId().equals(rentalId));
    }
}
