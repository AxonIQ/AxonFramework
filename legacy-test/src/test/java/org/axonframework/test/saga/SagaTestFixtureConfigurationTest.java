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
import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.test.AxonAssertionError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the configuration surface of {@link SagaTestFixture}: what it still exposes itself, and what it delegates to
 * the {@link org.axonframework.messaging.core.configuration.MessagingConfigurer MessagingConfigurer} an application
 * configures too.
 *
 * @author Mateusz Nowak
 */
class SagaTestFixtureConfigurationTest {

    private final SagaTestFixture<OrderSaga> fixture = new SagaTestFixture<>(OrderSaga.class);

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Nested
    class Resources {

        @Test
        void aRegisteredResourceResolvesAHandlerParameter() {
            fixture.registerResource(new Notifier("first"));

            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderNotified("shipment-of-order-1"))
                   .expectAssociationWith("notifier", "first");
        }

        @Test
        void givenNoPriorActivityDoesNotFreezeFixtureConfiguration() {
            fixture.givenNoPriorActivity();
            fixture.registerResource(new Notifier("registered-after-given"));

            fixture.whenPublishingA(new OrderPlaced("order-1"));
            fixture.whenPublishingA(new OrderNotified("shipment-of-order-1"))
                   .expectAssociationWith("notifier", "registered-after-given");
        }

        @Test
        void selectingAGivenAggregateDoesNotFreezeFixtureConfiguration() {
            GivenAggregateEventPublisher publisher = fixture.givenAggregate("order-1");
            fixture.registerResource(new Notifier("registered-after-aggregate"));

            publisher.published(new OrderPlaced("order-1"))
                     .whenPublishingA(new OrderNotified("shipment-of-order-1"))
                     .expectAssociationWith("notifier", "registered-after-aggregate");
        }

        /**
         * Inherited from Axon Framework 4, which prepended each registered resource so the most recently registered one
         * of a type was found first.
         */
        @Test
        void theLastRegisteredResourceOfATypeWins() {
            fixture.registerResource(new Notifier("first"));
            fixture.registerResource(new Notifier("second"));

            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderNotified("shipment-of-order-1"))
                   .expectAssociationWith("notifier", "second");
        }

        @Test
        void aParameterResolverFactoryIsConsulted() {
            fixture.registerParameterResolverFactory(
                    (executable, parameters, index) -> parameters[index].getType().equals(Notifier.class)
                            ? new FixedNotifierResolver(new Notifier("from-factory"))
                            : null
            );

            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderNotified("shipment-of-order-1"))
                   .expectAssociationWith("notifier", "from-factory");
        }
    }

    @Nested
    class EventHandlerInterceptors {

        @Test
        void interceptorsAreInvokedInRegistrationOrder() {
            List<String> invoked = new CopyOnWriteArrayList<>();
            fixture.registerEventHandlerInterceptor((message, context, chain) -> {
                invoked.add("first");
                return chain.proceed(message, context);
            });
            fixture.registerEventHandlerInterceptor((message, context, chain) -> {
                invoked.add("second");
                return chain.proceed(message, context);
            });

            fixture.givenNoPriorActivity()
                   .whenPublishingA(new OrderPlaced("order-1"))
                   .expectActiveSagas(1);

            assertThat(invoked).containsExactly("first", "second");
        }

        /**
         * The fixture's own interceptor, which carries the aggregate envelope, is registered before any the test adds.
         * A test interceptor therefore sees the processing context already populated and a message without the
         * metadata that carried the envelope there.
         */
        @Test
        void aRegisteredInterceptorSeesTheAggregateEnvelopeAlreadyLifted() {
            List<String> seen = new CopyOnWriteArrayList<>();
            fixture.registerEventHandlerInterceptor((message, context, chain) -> {
                seen.add(context.getResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY)
                                 + "/" + message.metadata().size());
                return chain.proceed(message, context);
            });

            fixture.givenAggregate("order-1").published(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectActiveSagas(1);

            assertThat(seen).first().isEqualTo("order-1/0");
        }
    }

    @Nested
    class GivenPhaseFailures {

        @Test
        void aFailureInTheGivenPhaseSurfacesByDefault() {
            assertThatThrownBy(() -> fixture.givenAPublished(new OrderCancelled("order-1")))
                    .rootCause()
                    .hasMessage("cannot cancel an order that was never placed");
        }

        /**
         * Axon Framework 4's {@code FixtureConfiguration} documented that failures in the "given" phase were suppressed
         * by default, while its implementation propagated them. The implementation is what is kept.
         */
        @Test
        void aFailureInTheGivenPhaseIsSuppressedWhenAsked() {
            fixture.suppressExceptionInGivenPhase(true);

            assertThatCode(() -> fixture.givenAPublished(new OrderCancelled("order-1"))
                                        .whenPublishingA(new OrderPlaced("order-1"))
                                        .expectActiveSagas(1))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class Customization {

        /**
         * The replacement for Axon Framework 4's {@code registerCommandGateway(Class, stub)}: a command handler
         * subscribed on the configurer decides what a dispatched command returns.
         */
        @Test
        void aStubCommandHandlerSubscribedOnTheConfigurerAnswersTheSaga() {
            fixture.customize(configurer -> configurer.componentRegistry(cr -> cr.registerDecorator(
                    CommandBus.class,
                    0,
                    (c, name, delegate) -> delegate.subscribe(
                            new QualifiedName(ConfirmOrder.class),
                            (command, context) -> MessageStream.just(new GenericCommandResultMessage(
                                    new MessageType(String.class), "confirmed"))
                    )
            )));

            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderConfirmationRequested("shipment-of-order-1"))
                   .expectDispatchedCommands(new ConfirmOrder("order-1"))
                   .expectAssociationWith("commandResult", "confirmed");
        }

        @Test
        void customizationsAreAppliedInRegistrationOrder() {
            List<String> applied = new CopyOnWriteArrayList<>();
            fixture.customize(configurer -> {
                applied.add("first");
                return configurer;
            });
            fixture.customize(configurer -> {
                applied.add("second");
                return configurer;
            });

            fixture.givenNoPriorActivity()
                   .whenPublishingA(new OrderPlaced("order-1"));

            assertThat(applied).containsExactly("first", "second");
        }
    }

    @Nested
    class FieldFilters {

        @Test
        void anIgnoredFieldIsNotComparedOnADispatchedCommand() {
            fixture.registerIgnoredField(ConfirmOrder.class, "orderId");

            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectDispatchedCommands(new ConfirmOrder("another-order"));
        }

        @Test
        void aFieldThatIsNotIgnoredIsStillCompared() {
            assertThatThrownBy(() -> fixture.givenAPublished(new OrderPlaced("order-1"))
                                            .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                                            .expectDispatchedCommands(new ConfirmOrder("another-order")))
                    .isInstanceOf(AxonAssertionError.class);
        }
    }

    @Nested
    class RecordingCallbacks {

        @Test
        void theCallbackRunsWhenTheWhenPhaseBegins() {
            List<String> calls = new CopyOnWriteArrayList<>();
            fixture.registerStartRecordingCallback(() -> calls.add("started"));

            fixture.givenAPublished(new OrderPlaced("order-1"));
            assertThat(calls).isEmpty();

            fixture.whenPublishingA(new OrderShipped("shipment-of-order-1"));
            assertThat(calls).containsExactly("started");
        }

        @Test
        void theCallbackRunsForEachWhenPublishingAInvocation() {
            List<String> calls = new CopyOnWriteArrayList<>();
            fixture.registerStartRecordingCallback(() -> calls.add("started"));

            fixture.givenAPublished(new OrderPlaced("order-1"));
            fixture.whenPublishingA(new OrderShipped("shipment-of-order-1"));
            fixture.whenPublishingA(new OrderShipped("shipment-of-order-1"));

            assertThat(calls).containsExactly("started", "started");
        }
    }

    @Nested
    class Components {

        /**
         * Axon Framework 4 returned the whole {@code EventBus} here, so a test could subscribe to it. Axon Framework 5
         * splits publishing off into an {@link EventSink}, and returning only that half would break such a test.
         */
        @Test
        void theEventBusIsReachableAndCanBeSubscribedTo() {
            fixture.givenNoPriorActivity();

            EventBus eventBus = fixture.getEventBus();
            assertThat(eventBus).isInstanceOf(EventSink.class)
                                .isInstanceOf(SubscribableEventSource.class);

            List<EventMessage> seen = new CopyOnWriteArrayList<>();
            eventBus.subscribe((events, context) -> {
                seen.addAll(events);
                return CompletableFuture.completedFuture(null);
            });

            fixture.whenPublishingA(new OrderPlaced("order-1")).expectActiveSagas(1);

            assertThat(seen).extracting(EventMessage::payload).containsExactly(new OrderPlaced("order-1"));
        }

        @Test
        void theCommandBusIsReachable() {
            fixture.givenNoPriorActivity();

            assertThat(fixture.getCommandBus()).isNotNull();
        }
    }

    /**
     * Inherited from Axon Framework 4, which wired the fixture once and ignored anything registered afterwards.
     */
    @Nested
    class WiredOnce {

        @Test
        void aResourceRegisteredAfterTheFirstGivenIsIgnored() {
            fixture.registerResource(new Notifier("first"));
            fixture.givenAPublished(new OrderPlaced("order-1"));
            fixture.registerResource(new Notifier("too-late"));

            fixture.whenPublishingA(new OrderNotified("shipment-of-order-1"))
                   .expectAssociationWith("notifier", "first");
        }

        @Test
        void requestingABusBuildsTheAxonFramework5Configuration() {
            fixture.registerResource(new Notifier("before-bus-access"));
            fixture.getEventBus();
            fixture.registerResource(new Notifier("after-bus-access"));

            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderNotified("shipment-of-order-1"))
                   .expectAssociationWith("notifier", "before-bus-access");
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void closingTheFixtureRunsConfigurationShutdownHandlers() {
            AtomicBoolean shutdown = new AtomicBoolean();
            fixture.customize(configurer -> configurer.lifecycleRegistry(
                    lifecycle -> lifecycle.onShutdown(() -> shutdown.set(true))
            ));

            try (fixture) {
                fixture.givenNoPriorActivity()
                       .whenPublishingA(new OrderPlaced("order-1"))
                       .expectActiveSagas(1);
            }

            assertThat(shutdown).isTrue();
        }
    }

    private static String shipmentIdFor(String orderId) {
        return "shipment-of-" + orderId;
    }

    public record OrderPlaced(String orderId) {

    }

    public record OrderShipped(String shipmentId) {

    }

    public record OrderConfirmationRequested(String shipmentId) {

    }

    public record OrderNotified(String shipmentId) {

    }

    public record OrderCancelled(String orderId) {

    }

    public record ConfirmOrder(String orderId) {

    }

    public record Notifier(String name) {

    }

    @SuppressWarnings({"unused", "removal"})
    public static class OrderSaga {

        private String orderId;

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event, SagaLifecycle lifecycle) {
            this.orderId = event.orderId();
            lifecycle.associateWith("shipmentId", shipmentIdFor(event.orderId()));
        }

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderCancelled event) {
            throw new IllegalStateException("cannot cancel an order that was never placed");
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderShipped event, CommandDispatcher commands) {
            commands.send(new ConfirmOrder(orderId));
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderNotified event, SagaLifecycle lifecycle, Notifier notifier) {
            lifecycle.associateWith("notifier", notifier.name());
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderConfirmationRequested event, SagaLifecycle lifecycle, CommandDispatcher commands) {
            Object result = commands.send(new ConfirmOrder(orderId))
                                    .getResultMessage()
                                    .orTimeout(5, TimeUnit.SECONDS)
                                    .join()
                                    .payload();
            lifecycle.associateWith("commandResult", String.valueOf(result));
        }
    }

    private record FixedNotifierResolver(Notifier notifier)
            implements org.axonframework.messaging.core.annotation.ParameterResolver<Notifier> {

        @Override
        public java.util.concurrent.CompletableFuture<Notifier> resolveParameterValue(
                org.axonframework.messaging.core.unitofwork.ProcessingContext context
        ) {
            return java.util.concurrent.CompletableFuture.completedFuture(notifier);
        }

        @Override
        public boolean matches(org.axonframework.messaging.core.unitofwork.ProcessingContext context) {
            return true;
        }
    }
}
