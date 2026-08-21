package org.axonframework.examples.sagarecipes.saga.shared;

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.messaging.core.ConfigurationApplicationContext;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RentalPaymentSequencingPolicyTest {
    @Nested
    class SerializedPayloads {
        @Test
        void rentalAndPaymentEventsForSameRentalHaveEqualSequenceIdentifiers() {
            RentalId rentalId = RentalId.random();
            var reference = RentalPaymentReference.forRental(rentalId);
            var rentalEvent = new BikeRequested(BikeId.random(), "renter", rentalId);
            var paymentEvent = new PaymentConfirmed(PaymentId.random(), reference);
            var otherPaymentEvent = new PaymentConfirmed(
                    PaymentId.random(), RentalPaymentReference.forRental(RentalId.random()));
            var policy = new RentalPaymentSequencingPolicy();
            UnitOfWork unitOfWork = context();

            unitOfWork.executeWithResult(context -> {
                var rentalSequence = policy.sequenceIdentifierFor(serialized(rentalEvent), context);
                var paymentSequence = policy.sequenceIdentifierFor(serialized(paymentEvent), context);
                var otherSequence = policy.sequenceIdentifierFor(serialized(otherPaymentEvent), context);
                assertThat(rentalSequence).isEqualTo(paymentSequence).hasValue(rentalId.raw());
                assertThat(otherSequence).isNotEqualTo(rentalSequence);
                return CompletableFuture.completedFuture(null);
            }).orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(RentalPaymentReference.toRental(reference)).isEqualTo(rentalId);
        }
    }

    private static GenericEventMessage serialized(Object event) {
        var converter = new JacksonConverter();
        byte[] bytes = converter.convert(event, byte[].class);
        return new GenericEventMessage(new MessageType(event.getClass()), bytes);
    }

    private static UnitOfWork context() {
        var configuration = MessagingConfigurer.create()
                .componentRegistry(registry -> registry.registerComponent(
                        EventConverter.class, ignored -> new DelegatingEventConverter(new JacksonConverter())))
                .build();
        return new SimpleUnitOfWorkFactory(new ConfigurationApplicationContext(configuration)).create(config -> config);
    }
}
