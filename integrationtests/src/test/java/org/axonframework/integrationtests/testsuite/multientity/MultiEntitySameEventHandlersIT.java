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

package org.axonframework.integrationtests.testsuite.multientity;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.AbstractIT;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.repository.ManagedEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration test proving that when a single command handler loads two entities in one unit of work, an appended event
 * is only applied to the entity it belongs to (as determined by the entity's {@link EventCriteria}/tags) — not to every
 * loaded entity that happens to declare an {@link EventSourcingHandler} for that event type.
 * <p>
 * The scenario is the "speedometer moved between bikes" case: two {@code RentableBike} entities are injected into one
 * {@code MoveSpeedometerCommand} handler. Appending {@code SpeedoMeterRemoved} for {@code bike1} must not evolve
 * {@code bike2}. The two bikes are the same class here, so they trivially share the same event-sourcing handlers, which
 * is exactly the condition that triggers the defect: the trigger is <em>shared event handlers on co-loaded entities</em>,
 * not the entity <em>type</em>.
 */
public abstract class MultiEntitySameEventHandlersIT extends AbstractIT {

    private static final String BIKE_1 = "bike1";
    private static final String BIKE_2 = "bike2";
    private static final SpeedometerId SPEEDO = new SpeedometerId("speedo1");

    protected UnitOfWorkFactory unitOfWorkFactory;

    @Override
    protected ApplicationConfigurer applicationConfigurer() {
        var configurer = EventSourcingConfigurer.create();
        var bikeEntity = EventSourcedEntityModule.autodetected(String.class, RentableBike.class);
        var commandHandlingModule = CommandHandlingModule.named("move-speedometer")
                                                         .commandHandlers()
                                                         .autodetectedCommandHandlingComponent(c -> new DomainService())
                                                         .build();
        return configurer.registerEntity(bikeEntity)
                         .registerCommandHandlingModule(commandHandlingModule);
    }

    @BeforeEach
    void doStartApp() {
        super.startApp();
        unitOfWorkFactory = startedConfiguration.getComponent(UnitOfWorkFactory.class);
    }

    @Test
    void appendedEventEvolvesOnlyTheEntityItBelongsTo() {
        // given — bike1 has a speedometer mounted, bike2 exists without one
        publish(new RentableBikeWasAdded(BIKE_1));
        publish(new SpeedoMeterMounted(BIKE_1, SPEEDO));
        publish(new RentableBikeWasAdded(BIKE_2));

        // when — a single handler loads both bikes and moves the speedometer from bike1 to bike2
        sendCommand(new MoveSpeedometerCommand(BIKE_1, BIKE_2));

        // then — SpeedoMeterRemoved(bike1) must not have been applied to bike2, and the move succeeds
        assertNull(loadBike(BIKE_1).mountedSpeedometer(), "bike1 should no longer have a speedometer");
        assertEquals(SPEEDO, loadBike(BIKE_2).mountedSpeedometer(), "bike2 should now hold the speedometer");
    }

    private void sendCommand(Object command) {
        commandGateway.send(command).getResultMessage().join();
    }

    private void publish(Object payload) {
        UnitOfWork uow = unitOfWorkFactory.create();
        var eventMessage = new GenericEventMessage(new MessageType(payload.getClass()), payload);
        uow.runOnInvocation(context -> context.component(EventGateway.class).publish(context, eventMessage));
        uow.execute().join();
    }

    private RentableBike loadBike(String id) {
        UnitOfWork uow = unitOfWorkFactory.create();
        return uow.executeWithResult(context -> context.component(StateManager.class)
                                                       .repository(RentableBike.class, String.class)
                                                       .load(id, context)
                                                       .thenApply(ManagedEntity::entity))
                  .join();
    }

    // ========== Supporting domain (self-contained, adapted from the reported SpeedometerMigrationTest) ==========

    record SpeedometerId(String id) {}

    record MoveSpeedometerCommand(String fromBikeId, String toBikeId) {}

    record RentableBikeWasAdded(@EventTag(key = "RentableBike") String bikeId) {}

    record SpeedoMeterMounted(@EventTag(key = "RentableBike") String bikeId, SpeedometerId speedometerId) {}

    record SpeedoMeterRemoved(@EventTag(key = "RentableBike") String bikeId, SpeedometerId speedometerId) {}

    @EventSourcedEntity
    public static class RentableBike {

        private String bikeId;
        private SpeedometerId mountedSpeedometerId;

        @EntityCreator
        public RentableBike() {
        }

        public SpeedometerId mountedSpeedometer() {
            return mountedSpeedometerId;
        }

        Optional<SpeedometerId> removeSpeedoMeter(EventAppender eventAppender) {
            SpeedometerId speedometerId = this.mountedSpeedometerId;
            eventAppender.append(new SpeedoMeterRemoved(bikeId, speedometerId));
            return Optional.ofNullable(speedometerId);
        }

        void mountSpeedometer(SpeedometerId speedometerId, EventAppender eventAppender) {
            eventAppender.append(new SpeedoMeterMounted(bikeId, speedometerId));
        }

        @EventSourcingHandler
        void on(RentableBikeWasAdded event) {
            this.bikeId = event.bikeId();
        }

        @EventSourcingHandler
        void on(SpeedoMeterMounted event) {
            this.mountedSpeedometerId = event.speedometerId();
        }

        @EventSourcingHandler
        void on(SpeedoMeterRemoved event) {
            if (this.mountedSpeedometerId == null) {
                throw new IllegalStateException(
                        "No speedometer mounted on bike " + bikeId + " to remove. Event contains bike " + event.bikeId());
            }
            this.mountedSpeedometerId = null;
        }

        @EventCriteriaBuilder
        private static EventCriteria resolve(String id) {
            return EventCriteria.havingTags("RentableBike", id);
        }
    }

    public static class DomainService {

        @CommandHandler
        void handle(MoveSpeedometerCommand command,
                    @InjectEntity(idProperty = "fromBikeId") RentableBike fromBike,
                    @InjectEntity(idProperty = "toBikeId") RentableBike toBike,
                    EventAppender eventAppender) {
            SpeedometerId speedometerId = fromBike.removeSpeedoMeter(eventAppender)
                                                  .orElseThrow(() -> new IllegalStateException(
                                                          "No speedometer mounted on bike " + command.fromBikeId()));
            toBike.mountSpeedometer(speedometerId, eventAppender);
        }
    }
}
