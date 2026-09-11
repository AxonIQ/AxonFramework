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

import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.modelling.saga.StartSaga;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

/**
 * What a command dispatched by a Saga answers.
 * <p>
 * Axon Framework 4's fixture answered every command from a {@code CallbackBehavior}, so a command nobody handled
 * succeeded with {@code null} and a Saga awaiting the result carried on. Dispatching on an ordinary command bus
 * instead would fail with no handler found, which is a behavioural difference a migrated Saga notices only when it
 * waits for the answer.
 *
 * @author Mateusz Nowak
 */
class SagaTestFixtureCommandAnswerTest {

    private final SagaTestFixture<AwaitingSaga> fixture = new SagaTestFixture<>(AwaitingSaga.class);

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Nested
    class WithoutAnything {

        @Test
        void aCommandNobodyHandlesAnswersNull() {
            fixture.givenNoPriorActivity()
                   .whenPublishingA(new OrderPlaced("order-1"))
                   .expectAssociationWith("reply", "none")
                   .expectSuccessfulHandlerExecution();
        }

        @Test
        void theCommandIsStillRecorded() {
            fixture.givenNoPriorActivity()
                   .whenPublishingA(new OrderPlaced("order-1"))
                   .expectDispatchedCommands(new ConfirmOrder("order-1"));
        }
    }

    @Nested
    class WithACallbackBehavior {

        @Test
        void theBehaviourDecidesTheAnswer() {
            fixture.setCallbackBehavior((payload, metadata) -> "behaved");

            fixture.givenNoPriorActivity()
                   .whenPublishingA(new OrderPlaced("order-1"))
                   .expectAssociationWith("reply", "behaved");
        }

        @Test
        void aFailingBehaviourFailsTheDispatch() {
            fixture.setCallbackBehavior((payload, metadata) -> {
                throw new IllegalStateException("the behaviour said no");
            });

            fixture.givenNoPriorActivity()
                   .whenPublishingA(new OrderPlaced("order-1"))
                   .expectAssociationWith("reply", "failed: the behaviour said no");
        }

        @Test
        void theCommandIsStillRecorded() {
            fixture.setCallbackBehavior((payload, metadata) -> "behaved");

            fixture.givenNoPriorActivity()
                   .whenPublishingA(new OrderPlaced("order-1"))
                   .expectDispatchedCommands(new ConfirmOrder("order-1"));
        }

        @Test
        void theBehaviourCanBeChangedAfterTheFixtureHasStarted() {
            fixture.givenNoPriorActivity()
                   .whenPublishingA(new OrderPlaced("order-1"))
                   .expectAssociationWith("reply", "none");

            fixture.setCallbackBehavior((payload, metadata) -> "changed");

            fixture.whenPublishingA(new OrderPlaced("order-2"))
                   .expectAssociationWith("reply", "changed");
        }
    }

    /**
     * Axon Framework 4's fixture bus ignored subscriptions entirely. Honouring them adds nothing an Axon Framework 4
     * test can observe, since nothing subscribes a handler there, and it keeps a test written the Axon Framework 5 way
     * working.
     */
    @Nested
    class WithASubscribedHandler {

        @Test
        void theHandlerAnswersRatherThanTheBehaviour() {
            fixture.setCallbackBehavior((payload, metadata) -> "behaved");
            fixture.customize(configurer -> configurer.componentRegistry(cr -> cr.registerDecorator(
                    CommandBus.class,
                    0,
                    (c, name, delegate) -> delegate.subscribe(
                            new QualifiedName(ConfirmOrder.class),
                            (command, context) -> MessageStream.just(new GenericCommandResultMessage(
                                    new MessageType(String.class), "handled"))
                    )
            )));

            fixture.givenNoPriorActivity()
                   .whenPublishingA(new OrderPlaced("order-1"))
                   .expectAssociationWith("reply", "handled");
        }
    }

    public record OrderPlaced(String orderId) {

    }

    public record ConfirmOrder(String orderId) {

    }

    @SuppressWarnings({"unused", "removal"})
    public static class AwaitingSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event, SagaLifecycle lifecycle, CommandDispatcher commands) {
            String reply;
            try {
                Object payload = commands.send(new ConfirmOrder(event.orderId()))
                                         .getResultMessage()
                                         .orTimeout(5, TimeUnit.SECONDS)
                                         .join()
                                         .payload();
                reply = payload == null ? "none" : payload.toString();
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                reply = "failed: " + cause.getMessage();
            }
            lifecycle.associateWith("reply", reply);
        }
    }
}
