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

package org.axonframework.test.saga;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.Timestamp;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.SourceId;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.modelling.saga.StartSaga;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stub saga used to test various scenarios of the {@link FixtureConfiguration}.
 * <p>
 * Axon Framework 4 received its collaborators as {@code @Inject} annotated fields, filled in by a
 * {@code ResourceInjector}. Axon Framework 5 dropped field injection, so they arrive as handler method parameters
 * instead, which is why every handler needing one declares it.
 *
 * @author Allard Buijze
 */
@SuppressWarnings({"unused", "removal"})
public class StubSaga {

    private static final int TRIGGER_DURATION_MINUTES = 10;
//    TODO #5006
//    @Inject
//    private transient EventScheduler scheduler;

    private final List<Object> handledEvents = new ArrayList<>();

//    private ScheduleToken timer;

    @StartSaga
    @SagaEventHandler(associationProperty = "identifier")
    public void handleSagaStart(TriggerSagaStartEvent event,
                                SagaLifecycle lifecycle,
                                EventMessage message,
                                @MetadataValue("extraIdentifier") Object extraIdentifier) {
        handledEvents.add(event);

        if (extraIdentifier != null) {
            associateWith(lifecycle, "extraIdentifier", extraIdentifier.toString());
        }

//        TODO #5006
//        timer = scheduler.schedule(
//                message.timestamp().plus(TRIGGER_DURATION_MINUTES, ChronoUnit.MINUTES),
//                new GenericEventMessage(
//                        new MessageType("event"), new TimerTriggeredEvent(event.getIdentifier())
//                )
//        );
    }

    @StartSaga(forceNew = true)
    @SagaEventHandler(associationProperty = "identifier")
    public void handleForcedSagaStart(ForceTriggerSagaStartEvent event, @Timestamp Instant timestamp) {
        handledEvents.add(event);
//        TODO #5006
//        timer = scheduler.schedule(
//                timestamp.plus(TRIGGER_DURATION_MINUTES, ChronoUnit.MINUTES),
//                new GenericEventMessage(
//                        new MessageType("event"), new TimerTriggeredEvent(event.getIdentifier())
//                )
//        );
    }

    @SagaEventHandler(associationProperty = "identifier")
    public void handleEvent(TriggerExistingSagaEvent event, EventSink eventSink, ProcessingContext context) {
        handledEvents.add(event);
        eventSink.publish(context, new GenericEventMessage(
                new MessageType("event"), new SagaWasTriggeredEvent(this)
        ));
    }

    @SagaEventHandler(associationProperty = "identifier")
    public void handle(ParameterResolvedEvent event,
                       AtomicBoolean assertion,
                       CommandGateway commandGateway,
                       ProcessingContext context) {
        handledEvents.add(event);
        assertFalse(assertion.get());
        assertion.set(true);
        commandGateway.send(new ResolveParameterCommand(event.getIdentifier(), assertion), context);
    }

    @EndSaga
    @SagaEventHandler(associationProperty = "identifier")
    public void handleEndEvent(TriggerSagaEndEvent event) {
        handledEvents.add(event);
    }

    @SagaEventHandler(associationProperty = "identifier")
    public void handleFalseEvent(TriggerExceptionWhileHandlingEvent event) {
        handledEvents.add(event);
        throw new RuntimeException("This is a mock exception");
    }

    @SagaEventHandler(associationProperty = "identifier")
    public void handleTriggerEvent(TimerTriggeredEvent event,
                                   CommandGateway commandGateway,
                                   ProcessingContext context) {
        handledEvents.add(event);
        String result = commandGateway.send("Say hi!", String.class, context).join();
        if (result != null) {
            commandGateway.send(result, context);
        }
    }

    @SagaEventHandler(associationProperty = "identifier")
    public void handleResetTriggerEvent(ResetTriggerEvent event) {
        handledEvents.add(event);
//        TODO #5006
//        scheduler.cancelSchedule(timer);
//        timer = scheduler.schedule(
//                Duration.ofMinutes(TRIGGER_DURATION_MINUTES),
//                new GenericEventMessage(
//                        new MessageType("event"), new TimerTriggeredEvent(event.getIdentifier())
//                )
//        );
    }

    @SagaEventHandler(associationProperty = "identifier", associationResolver = AssociationResolverStub.class)
    public void handleTriggerAssociationResolverSagaEvent(TriggerAssociationResolverSagaEvent event) {
        handledEvents.add(event);
    }

//    TODO #5006
//    public EventScheduler getScheduler() {
//        return scheduler;
//    }

    public void associateWith(SagaLifecycle lifecycle, String key, String value) {
        lifecycle.associateWith(key, value);
    }

    public void removeAssociationWith(SagaLifecycle lifecycle, String key, String value) {
        lifecycle.removeAssociationWith(key, value);
    }

    public void end(SagaLifecycle lifecycle) {
        lifecycle.end();
    }
}
