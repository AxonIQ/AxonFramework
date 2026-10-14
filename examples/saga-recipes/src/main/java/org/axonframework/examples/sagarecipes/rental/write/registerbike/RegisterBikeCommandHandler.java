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

package org.axonframework.examples.sagarecipes.rental.write.registerbike;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Adds a bike to the fleet.
 * <p>
 * Registering a bike that already exists appends nothing and reports success. Every command handler in this module
 * is idempotent this way, because an event processor delivers at least once and a retried command must not produce
 * a second event.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Component
public class RegisterBikeCommandHandler {

    /**
     * Registers the bike, unless it is already known.
     *
     * @param command  the command to handle
     * @param state    the bike, or {@code null} if it has not been registered yet
     * @param appender appends the resulting event
     */
    @CommandHandler
    void handle(RegisterBike command, @InjectEntity @Nullable State state, EventAppender appender) {
        if (state != null) {
            return;
        }
        appender.append(new BikeRegistered(command.bikeId(), command.bikeType(), command.location()));
    }

    /**
     * Decision model for this slice: whether the bike exists at all.
     * <p>
     * Deliberately minimal. Other slices in this context source the same bike with different fields, because each
     * slice owns the narrowest consistency boundary its own rule needs.
     */
    @EventSourced(idType = BikeId.class)
    private static class State {

        @EntityCreator
        State(BikeRegistered event) {
        }

        /**
         * Registration cares about nothing but registration. Everything a bike goes through afterwards is another
         * slice's concern.
         *
         * @param bikeId the bike this decision concerns
         * @return the criteria selecting exactly the events this decision depends on
         */
        @EventCriteriaBuilder
        private static EventCriteria criteria(BikeId bikeId) {
            return EventCriteria.havingTags(Tag.of(RentalTags.BIKE_ID, bikeId.raw()))
                                .andBeingOneOfTypes(BikeRegistered.class.getName());
        }
    }
}
