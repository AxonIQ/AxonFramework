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

package org.axonframework.test.fixture;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.test.AxonAssertionError;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AxonTestFixtureWhenInputFilteringTest {

    private static final TestEvent INPUT_EVENT = new TestEvent("input");
    private static final TestEvent OUTPUT_EVENT = new TestEvent("output");

    @Nested
    class Events {

        @Test
        void eventInputIsFilteredOutByDefault() {
            var fixture = AxonTestFixture.with(configurer());

            fixture.when()
                   .event(INPUT_EVENT)
                   .then()
                   .noEvents();
        }

        @Test
        void handlerOutputRemainsVisible() {
            var configurer = configurer();
            publishOnFirstEvent(configurer, OUTPUT_EVENT);
            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .event(INPUT_EVENT)
                   .then()
                   .events(OUTPUT_EVENT);
        }

        @Test
        void filteringUsesIdentifiersSoEqualRepublishedPayloadRemainsVisible() {
            var configurer = configurer();
            publishOnFirstEvent(configurer, INPUT_EVENT);
            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .event(INPUT_EVENT)
                   .then()
                   .events(INPUT_EVENT);
        }

        @Test
        void explicitEventMessageIsFilteredByItsIdentifier() {
            var fixture = AxonTestFixture.with(configurer());
            EventMessage input = eventMessage(INPUT_EVENT);

            fixture.when()
                   .event(input, Metadata.with("key", "value"))
                   .then()
                   .noEvents();
        }

        @Test
        void filteringAppliesToCustomAssertions() {
            var configurer = configurer();
            publishOnFirstEvent(configurer, OUTPUT_EVENT);
            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .event(INPUT_EVENT)
                   .then()
                   .eventsSatisfy(events -> assertThat(events).extracting(EventMessage::payload)
                                                              .containsExactly(OUTPUT_EVENT))
                   .eventsMatch(events -> events.size() == 1);
        }

        @Test
        void allEventsInABatchAreFilteredOut() {
            var fixture = AxonTestFixture.with(configurer());

            fixture.when()
                   .events(INPUT_EVENT, OUTPUT_EVENT)
                   .then()
                   .noEvents();
        }

        @Test
        void givenEventsDoNotBecomeWhenInputs() {
            var fixture = AxonTestFixture.with(configurer());

            fixture.given()
                   .event(OUTPUT_EVENT)
                   .when()
                   .event(INPUT_EVENT)
                   .then()
                   .noEvents();
        }

        @Test
        void eachScenarioFiltersItsOwnInput() {
            var fixture = AxonTestFixture.with(configurer());

            fixture.when()
                   .event(INPUT_EVENT)
                   .then()
                   .noEvents()
                   .and()
                   .when()
                   .event(OUTPUT_EVENT)
                   .then()
                   .noEvents();
        }
    }

    @Nested
    class Commands {

        @Test
        void directCommandIsFilteredOutFromAllCommandAssertions() {
            var configurer = configurer();
            registerNoOpCommandHandler(configurer);
            var fixture = AxonTestFixture.with(configurer);
            TestCommand input = new TestCommand("input");

            fixture.when()
                   .command(input)
                   .then()
                   .noCommands()
                   .commandsSatisfy(commands -> assertThat(commands).isEmpty())
                   .commandsMatch(List::isEmpty);
        }

        @Test
        void commandDispatchedWhileHandlingAnInputRemainsVisible() {
            var configurer = configurer();
            registerNoOpCommandHandler(configurer);
            dispatchCommandOnFirstEvent(configurer, new TestCommand("output"));
            var fixture = AxonTestFixture.with(configurer);

            fixture.when()
                   .event(INPUT_EVENT)
                   .then()
                   .commands(new TestCommand("output"));
        }

        @Test
        void filteredCommandCannotBeAssertedAsOutput() {
            var configurer = configurer();
            registerNoOpCommandHandler(configurer);
            var fixture = AxonTestFixture.with(configurer);
            TestCommand input = new TestCommand("input");

            assertThatThrownBy(() -> fixture.when()
                                            .command(input)
                                            .then()
                                            .commands(input))
                    .isInstanceOf(AxonAssertionError.class);
        }
    }

    private static void publishOnFirstEvent(MessagingConfigurer configurer, TestEvent payload) {
        AtomicBoolean handled = new AtomicBoolean();
        configurer.componentRegistry(cr -> cr.registerDecorator(
                SubscribableEventSource.class,
                0,
                (c, n, delegate) -> {
                    delegate.subscribe((events, context) -> {
                        if (!handled.getAndSet(true)) {
                            return c.getComponent(EventSink.class).publish(context, List.of(eventMessage(payload)));
                        }
                        return CompletableFuture.completedFuture(null);
                    });
                    return delegate;
                }
        ));
    }

    private static void dispatchCommandOnFirstEvent(MessagingConfigurer configurer, TestCommand command) {
        AtomicBoolean handled = new AtomicBoolean();
        configurer.componentRegistry(cr -> cr.registerDecorator(
                SubscribableEventSource.class,
                0,
                (c, n, delegate) -> {
                    delegate.subscribe((events, context) -> {
                        if (!handled.getAndSet(true)) {
                            return c.getComponent(CommandGateway.class)
                                    .send(command, context)
                                    .getResultMessage();
                        }
                        return CompletableFuture.completedFuture(null);
                    });
                    return delegate;
                }
        ));
    }

    private static void registerNoOpCommandHandler(MessagingConfigurer configurer) {
        configurer.componentRegistry(cr -> cr.registerDecorator(
                CommandBus.class,
                0,
                (c, n, delegate) -> delegate.subscribe(
                        new QualifiedName(TestCommand.class),
                        (command, context) -> MessageStream.empty().cast()
                )
        ));
    }

    private static EventMessage eventMessage(TestEvent payload) {
        return new GenericEventMessage(new MessageType(TestEvent.class), payload, Metadata.emptyInstance());
    }

    private static MessagingConfigurer configurer() {
        return MessagingConfigurer.create().componentRegistry(ComponentRegistry::disableEnhancerScanning);
    }

    private record TestEvent(String value) {
    }

    private record TestCommand(String value) {
    }
}
