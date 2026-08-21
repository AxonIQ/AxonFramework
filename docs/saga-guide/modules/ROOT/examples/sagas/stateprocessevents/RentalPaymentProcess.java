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

package sagas.stateprocessevents;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import sagas.shared.RentalPaymentApi.BikeRequested;
import sagas.shared.RentalPaymentApi.PreparePayment;
import sagas.statedomainevents.RentalPaymentIdResolver;

import java.util.concurrent.CompletableFuture;

import static sagas.shared.RentalPaymentApi.PRICE;
import static sagas.shared.RentalPaymentApi.RENTAL_ID;
import static sagas.shared.RentalPaymentApi.paymentReferenceFor;

/**
 * Event-sourcing the process from facts it writes about itself.
 */
public class RentalPaymentProcess {

    // tag::process-events[]
    public record RentalPaymentRequested(
            @EventTag(key = RENTAL_ID) String rentalId,
            String bikeId,
            String renter,
            int amount
    ) {

    }

    public record RentalPaymentProcessCompleted(@EventTag(key = RENTAL_ID) String rentalId) {

    }
    // end::process-events[]

    // tag::record-command[]
    public record RecordPaymentRequested(
            @TargetEntityId String rentalId,
            String bikeId,
            String renter,
            int amount
    ) {

    }
    // end::record-command[]

    // tag::translating[]
    @Component
    public static class TranslatingProcess {

        @EventHandler
        public CompletableFuture<?> on(
                BikeRequested event,
                @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
                CommandDispatcher dispatcher
        ) {
            if (state != null) {
                return CompletableFuture.completedFuture(null);
            }
            return dispatcher.send(new PreparePayment(paymentReferenceFor(event.rentalId()), PRICE))
                             .getResultMessage() // <1>
                             .thenCompose(ignored -> dispatcher.send( // <2>
                                     new RecordPaymentRequested(event.rentalId(),
                                                                event.bikeId(),
                                                                event.renter(),
                                                                PRICE)
                             ).getResultMessage());
        }

        @CommandHandler
        public void handle(
                RecordPaymentRequested command,
                @InjectEntity @Nullable State state,
                EventAppender appender
        ) {
            if (state == null) { // <3>
                appender.append(new RentalPaymentRequested(command.rentalId(),
                                                           command.bikeId(),
                                                           command.renter(),
                                                           command.amount()));
            }
        }
    }
    // end::translating[]

    // tag::appending[]
    @Component
    public static class AppendingProcess {

        @EventHandler
        public CompletableFuture<?> on(
                BikeRequested event,
                @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
                CommandDispatcher dispatcher,
                EventAppender appender // <1>
        ) {
            if (state != null) {
                return CompletableFuture.completedFuture(null);
            }
            return dispatcher.send(new PreparePayment(paymentReferenceFor(event.rentalId()), PRICE))
                             .getResultMessage()
                             .thenRun(() -> appender.append(new RentalPaymentRequested( // <2>
                                     event.rentalId(), event.bikeId(), event.renter(), PRICE
                             )));
        }
    }
    // end::appending[]

    // tag::state[]
    @EventSourced(idType = String.class)
    static class State {

        String bikeId;
        String renter;
        boolean completed;

        @EntityCreator
        State(RentalPaymentRequested event) {
            this.bikeId = event.bikeId();
            this.renter = event.renter();
        }

        @EventSourcingHandler
        void evolve(RentalPaymentProcessCompleted event) {
            this.completed = true;
        }

        @EventCriteriaBuilder
        private static EventCriteria criteria(String rentalId) {
            return EventCriteria.havingTags(Tag.of(RENTAL_ID, rentalId))
                                .andBeingOneOfTypes(RentalPaymentRequested.class.getName(), // <1>
                                                    RentalPaymentProcessCompleted.class.getName());
        }
    }
    // end::state[]
}
