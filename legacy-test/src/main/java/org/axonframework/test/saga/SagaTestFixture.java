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

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MultiParameterResolverFactory;
import org.axonframework.messaging.core.annotation.SimpleResourceParameterResolverFactory;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.configuration.reflection.HandlerDefinitionUtils;
import org.axonframework.messaging.core.configuration.reflection.HandlerEnhancerDefinitionUtils;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.modelling.saga.configuration.Sagas;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.axonframework.test.fixture.AxonTestFixture;
import org.axonframework.test.fixture.AxonTestPhase;
import org.axonframework.test.fixture.AxonTestPhase.Given;
import org.axonframework.test.fixture.AxonTestPhase.When;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.util.CallbackBehavior;
import org.axonframework.test.util.DefaultCallbackBehavior;
import org.axonframework.test.matchers.IgnoreField;
import org.axonframework.test.matchers.MatchAllFieldFilter;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Fixture for testing Sagas in a given-when-then style, carrying the Axon Framework 4 API so a migrating test suite
 * keeps its shape.
 * <p>
 * A layer over {@link AxonTestFixture}: the Saga is registered on a subscribing event processor, which handles on the
 * publishing thread, so a "when" call has already completed by the time the assertions run and no waiting is needed.
 * The Sagas are kept in an {@link InMemorySagaStore}, which the store assertions read.
 * <p>
 * When given an event payload, the fixture resolves its Axon Framework 5 message type from that payload. When given an
 * {@link EventMessage}, it preserves the message's declared type. Axon Framework 5 routes by that type, so it must match
 * the type supported by the intended Saga handler even when the message payload has the handler's Java type.
 * <p>
 * Example:
 * <pre>{@code
 * SagaTestFixture<OrderSaga> fixture = new SagaTestFixture<>(OrderSaga.class);
 * fixture.givenAPublished(new OrderPlaced("order-1"))
 *        .whenPublishingA(new OrderShipped("shipment-of-order-1"))
 *        .expectActiveSagas(1)
 *        .expectAssociationWith("orderId", "order-1");
 * }</pre>
 * A test that does not need the Axon Framework 4 API can drive the same Saga through {@code AxonTestFixture} directly
 * and use {@link SagaAssertions} for the store assertions.
 *
 * @param <T> the type of Saga under test
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class SagaTestFixture<T> implements FixtureConfiguration, ContinuedGivenState, AutoCloseable {

    private final Class<T> sagaType;
    private final InMemorySagaStore sagaStore = new InMemorySagaStore();
    private final Map<String, AggregateEventPublisher> aggregatePublishers = new HashMap<>();

    // Prepended, so the last registered resource of a type wins, as in Axon Framework 4.
    private final Deque<Object> resources = new ArrayDeque<>();
    private final Deque<ParameterResolverFactory> parameterResolverFactories = new ArrayDeque<>();
    private final Deque<HandlerDefinition> handlerDefinitions = new ArrayDeque<>();
    private final Deque<HandlerEnhancerDefinition> handlerEnhancerDefinitions = new ArrayDeque<>();
    // Appended, so interceptors are invoked in registration order, as in Axon Framework 4.
    private final List<MessageHandlerInterceptor<? super EventMessage>> eventHandlerInterceptors = new ArrayList<>();
    private final List<FieldFilter> fieldFilters = new ArrayList<>();
    private final List<Runnable> startRecordingCallbacks = new ArrayList<>();

    private UnaryOperator<MessagingConfigurer> customization = c -> c;
    private boolean suppressExceptionInGivenPhase = false;
    private volatile CallbackBehavior callbackBehavior = new DefaultCallbackBehavior();

    @Nullable
    private AxonTestFixture fixture;
    @Nullable
    private Given givenPhase;
    @Nullable
    private When whenPhase;
    @Nullable
    private volatile FixtureCommandBus fixtureCommandBus;

    /**
     * Creates an instance for testing Sagas of the given {@code sagaType}.
     *
     * @param sagaType the type of Saga under test
     */
    public SagaTestFixture(Class<T> sagaType) {
        this.sagaType = Objects.requireNonNull(sagaType, "The sagaType may not be null.");
    }

    @Deprecated(forRemoval = true)
    @Override
    public FixtureConfiguration withTransienceCheckDisabled() {
        return this;
    }

    @Override
    public FixtureConfiguration customize(UnaryOperator<MessagingConfigurer> customization) {
        Objects.requireNonNull(customization, "The customization may not be null.");
        UnaryOperator<MessagingConfigurer> current = this.customization;
        this.customization = configurer -> customization.apply(current.apply(configurer));
        return this;
    }

    @Override
    public void registerResource(Object resource) {
        resources.addFirst(Objects.requireNonNull(resource, "The resource may not be null."));
    }

    @Override
    public FixtureConfiguration registerParameterResolverFactory(ParameterResolverFactory parameterResolverFactory) {
        parameterResolverFactories.addFirst(
                Objects.requireNonNull(parameterResolverFactory, "The parameterResolverFactory may not be null.")
        );
        return this;
    }

    @Override
    public FixtureConfiguration registerHandlerDefinition(HandlerDefinition handlerDefinition) {
        handlerDefinitions.addFirst(Objects.requireNonNull(handlerDefinition, "The handlerDefinition may not be null."));
        return this;
    }

    @Override
    public FixtureConfiguration registerHandlerEnhancerDefinition(HandlerEnhancerDefinition definition) {
        handlerEnhancerDefinitions.addFirst(
                Objects.requireNonNull(definition, "The handlerEnhancerDefinition may not be null.")
        );
        return this;
    }

    @Override
    public FixtureConfiguration registerEventHandlerInterceptor(
            MessageHandlerInterceptor<? super EventMessage> interceptor
    ) {
        eventHandlerInterceptors.add(Objects.requireNonNull(interceptor, "The interceptor may not be null."));
        return this;
    }

    @Override
    public FixtureConfiguration registerFieldFilter(FieldFilter fieldFilter) {
        fieldFilters.add(Objects.requireNonNull(fieldFilter, "The fieldFilter may not be null."));
        return this;
    }

    @Override
    public FixtureConfiguration registerIgnoredField(Class<?> declaringClass, String fieldName) {
        return registerFieldFilter(new IgnoreField(declaringClass, fieldName));
    }

    @Override
    public FixtureConfiguration registerStartRecordingCallback(Runnable callback) {
        startRecordingCallbacks.add(Objects.requireNonNull(callback, "The callback may not be null."));
        return this;
    }

    @Override
    public void setCallbackBehavior(CallbackBehavior callbackBehavior) {
        this.callbackBehavior = Objects.requireNonNull(callbackBehavior, "The callbackBehavior may not be null.");
        FixtureCommandBus initializedCommandBus = fixtureCommandBus;
        if (initializedCommandBus != null) {
            initializedCommandBus.setCallbackBehavior(callbackBehavior);
        }
    }

    @Override
    public FixtureConfiguration suppressExceptionInGivenPhase(boolean suppress) {
        this.suppressExceptionInGivenPhase = suppress;
        return this;
    }

    @Override
    public EventBus getEventBus() {
        return configuration().getComponent(EventBus.class);
    }

    @Override
    public CommandBus getCommandBus() {
        return configuration().getComponent(CommandBus.class);
    }

    @Override
    public GivenAggregateEventPublisher givenAggregate(String aggregateIdentifier) {
        return publisherFor(aggregateIdentifier);
    }

    @Override
    public ContinuedGivenState givenAPublished(Object event) {
        return givenAPublished(event, Map.of());
    }

    @Override
    public ContinuedGivenState givenAPublished(Object event, Map<String, String> metadata) {
        Given phase = given();
        try {
            givenPhase = phase.event(event, metadata);
        } catch (RuntimeException e) {
            if (!suppressExceptionInGivenPhase) {
                throw e;
            }
            givenPhase = phase;
        }
        return this;
    }

    @Override
    public WhenState givenNoPriorActivity() {
        return this;
    }

    @Override
    public GivenAggregateEventPublisher andThenAggregate(String aggregateIdentifier) {
        return givenAggregate(aggregateIdentifier);
    }

    @Override
    public ContinuedGivenState andThenAPublished(Object event) {
        return givenAPublished(event);
    }

    @Override
    public ContinuedGivenState andThenAPublished(Object event, Map<String, String> metadata) {
        return givenAPublished(event, metadata);
    }

    @Override
    public WhenAggregateEventPublisher whenAggregate(String aggregateIdentifier) {
        // Axon Framework 4 started recording here, before handing out the publisher. Entering the when-phase resets
        // the recorders, which is the same moment.
        startWhenPhase();
        return publisherFor(aggregateIdentifier);
    }

    @Override
    public FixtureExecutionResult whenPublishingA(Object event) {
        return publishInWhen(startWhenPhase(), event, Map.of());
    }

    @Override
    public FixtureExecutionResult whenPublishingA(Object event, Map<String, String> metadata) {
        return publishInWhen(startWhenPhase(), event, metadata);
    }

    @Override
    public ContinuedGivenState givenCurrentTime(Instant currentTime) {
        // TODO #5006 - Axon Framework 4:
        // eventScheduler.initializeAt(currentTime);
        // deadlineManager.initializeAt(currentTime);
        // return this;
        throw NotPorted.deadlines("givenCurrentTime");
    }

    @Override
    public ContinuedGivenState andThenTimeElapses(Duration elapsedTime) {
        // TODO #5006 - Axon Framework 4:
        // eventScheduler.advanceTimeBy(elapsedTime, this::handleInSaga);
        // deadlineManager.advanceTimeBy(elapsedTime, this::handleDeadline);
        // return this;
        throw NotPorted.deadlines("andThenTimeElapses");
    }

    @Override
    public ContinuedGivenState andThenTimeAdvancesTo(Instant newDateTime) {
        // TODO #5006 - Axon Framework 4:
        // eventScheduler.advanceTimeTo(newDateTime, this::handleInSaga);
        // deadlineManager.advanceTimeTo(newDateTime, this::handleDeadline);
        // return this;
        throw NotPorted.deadlines("andThenTimeAdvancesTo");
    }

    @Override
    public FixtureExecutionResult whenTimeElapses(Duration elapsedTime) {
        // TODO #5006 - Axon Framework 4:
        // try {
        //     fixtureExecutionResult.startRecording();
        //     eventScheduler.advanceTimeBy(elapsedTime, this::handleInSaga);
        //     deadlineManager.advanceTimeBy(elapsedTime, this::handleDeadline);
        // } catch (Exception e) {
        //     throw new FixtureExecutionException("Exception occurred while trying to advance time "
        //                                                 + "and handle scheduled events", e);
        // }
        // return fixtureExecutionResult;
        throw NotPorted.deadlines("whenTimeElapses");
    }

    @Override
    public FixtureExecutionResult whenTimeAdvancesTo(Instant newDateTime) {
        // TODO #5006 - Axon Framework 4:
        // try {
        //     fixtureExecutionResult.startRecording();
        //     eventScheduler.advanceTimeTo(newDateTime, this::handleInSaga);
        //     deadlineManager.advanceTimeTo(newDateTime, this::handleDeadline);
        // } catch (Exception e) {
        //     throw new FixtureExecutionException("Exception occurred while trying to advance time "
        //                                                 + "and handle scheduled events", e);
        // }
        // return fixtureExecutionResult;
        throw NotPorted.deadlines("whenTimeAdvancesTo");
    }

    @Override
    public Instant currentTime() {
        // TODO #5006 - Axon Framework 4:
        // return eventScheduler.getCurrentDateTime();
        throw NotPorted.deadlines("currentTime");
    }

    /**
     * Shuts the configuration this fixture started down.
     * <p>
     * New in Axon Framework 5: the fixture runs a started {@code AxonConfiguration}, which holds an event processor
     * that has to be stopped.
     */
    public void stop() {
        if (fixture != null) {
            fixture.stop();
        }
    }

    /**
     * Shuts down the configuration started by this fixture.
     * <p>
     * This alias for {@link #stop()} allows a fixture to be used in a try-with-resources statement, ensuring that Axon
     * Framework 5 lifecycle handlers run even when a test fails.
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * The given-phase of the delegate fixture, starting the configuration on first use.
     * <p>
     * Starting lazily reproduces the Axon Framework 4 behaviour that the fixture is wired once, when the first event is
     * handled, and that anything configured after that point is ignored. Accessing a bus also starts the configuration,
     * because Axon Framework 5 obtains both buses from the started application configuration.
     */
    private Given given() {
        if (givenPhase == null) {
            AxonTestFixture.Customization customization = new AxonTestFixture.Customization();
            for (FieldFilter fieldFilter : fieldFilters) {
                customization = customization.registerFieldFilter(fieldFilter);
            }
            AxonTestFixture.Customization fixtureCustomization = customization;
            fixture = AxonTestFixture.with(configurer(), c -> fixtureCustomization);
            givenPhase = fixture.given();
        }
        return givenPhase;
    }

    private When when() {
        if (whenPhase == null) {
            return startWhenPhase();
        }
        return whenPhase;
    }

    private When startWhenPhase() {
        whenPhase = given().when();
        startRecordingCallbacks.forEach(Runnable::run);
        return whenPhase;
    }

    /**
     * The publisher for the given {@code aggregateIdentifier}, cached so its sequence numbers keep counting across the
     * given and when phases, as they did in Axon Framework 4.
     */
    private AggregateEventPublisher publisherFor(String aggregateIdentifier) {
        return aggregatePublishers.computeIfAbsent(aggregateIdentifier, AggregateEventPublisher::new);
    }

    private AxonConfiguration configuration() {
        given();
        return fixture.configuration();
    }

    private MessagingConfigurer configurer() {
        MessagingConfigurer configurer = MessagingConfigurer
                .create()
                .componentRegistry(cr -> cr.registerComponent(SagaStore.class, c -> sagaStore))
                // Decorating rather than replacing keeps whatever command bus the configuration builds, so a command
                // reaching a subscribed handler is still handled by it.
                .componentRegistry(cr -> cr.registerDecorator(
                        CommandBus.class,
                        Integer.MIN_VALUE,
                        (config, name, commandBus) -> {
                            FixtureCommandBus decoratedCommandBus = new FixtureCommandBus(commandBus);
                            fixtureCommandBus = decoratedCommandBus;
                            decoratedCommandBus.setCallbackBehavior(callbackBehavior);
                            return decoratedCommandBus;
                        }
                ))
                .componentRegistry(this::registerReflectionComponents)
                .eventProcessing(processing -> processing.subscribing(
                        subscribing -> subscribing.defaultProcessor(sagaType.getSimpleName(), this::sagaComponents)
                ));
        return customization.apply(configurer);
    }

    private EventHandlingComponentsConfigurer.CompletePhase sagaComponents(
            EventHandlingComponentsConfigurer.RequiredComponentPhase components
    ) {
        EventHandlingComponentsConfigurer.CompletePhase phase =
                components.declarative("Saga[" + sagaType.getSimpleName() + "]", Sagas.of(sagaType))
                          // Registered first, so an interceptor the test registers already sees the aggregate fields
                          // on the processing context and a message without the metadata that carried them.
                          .intercepted(c -> LegacyAggregateEnvelope.liftingInterceptor());
        for (MessageHandlerInterceptor<? super EventMessage> interceptor : eventHandlerInterceptors) {
            phase = phase.intercepted(c -> interceptor);
        }
        return phase;
    }

    private void registerReflectionComponents(ComponentRegistry registry) {
        if (!parameterResolverFactories.isEmpty() || !resources.isEmpty()) {
            registry.registerDecorator(
                    ParameterResolverFactory.class,
                    Integer.MAX_VALUE,
                    (config, name, classpathFactories) -> resolversPreceding(classpathFactories)
            );
        }
        handlerDefinitions.forEach(
                definition -> HandlerDefinitionUtils.registerToComponentRegistry(registry, c -> definition)
        );
        handlerEnhancerDefinitions.forEach(
                definition -> HandlerEnhancerDefinitionUtils.registerToComponentRegistry(registry, c -> definition)
        );
    }

    /**
     * The factories registered on this fixture, ahead of the {@code classpathFactories}, in the Axon Framework 4 order:
     * the ones registered explicitly, most recent first, and then the registered resources.
     * <p>
     * Composed with the plain {@link MultiParameterResolverFactory} constructor rather than
     * {@link MultiParameterResolverFactory#ordered(List)}, because the order here is the Axon Framework 4 registration
     * order rather than a {@link org.axonframework.common.Priority @Priority} ranking. Registering it as the outermost
     * decorator is what makes that order hold: the configuration composes its own factories by nesting decorators, and
     * a priority only ranks the factories within one level.
     * <p>
     * The precedence is load-bearing, not a preference. {@code axon-test} contributes a classpath factory that matches
     * every parameter and fails when used, so an unregistered resource is reported by name. Consulted first, it claims
     * the very parameters this fixture knows how to resolve.
     */
    private ParameterResolverFactory resolversPreceding(ParameterResolverFactory classpathFactories) {
        List<ParameterResolverFactory> factories = new ArrayList<>(parameterResolverFactories);
        if (!resources.isEmpty()) {
            factories.add(new SimpleResourceParameterResolverFactory(List.copyOf(resources)));
        }
        factories.add(classpathFactories);
        return new MultiParameterResolverFactory(factories);
    }

    private FixtureExecutionResult publishInWhen(When phase,
                                                 Object event,
                                                 Map<String, String> metadata) {
        return resultOf(phase.event(event, metadata));
    }

    private FixtureExecutionResult resultOf(AxonTestPhase.When.Event event) {
        return new FixtureExecutionResultImpl(
                sagaType,
                event.then(),
                new MatchAllFieldFilter(fieldFilters)
        );
    }

    /**
     * Publishes events on behalf of an aggregate.
     * <p>
     * Axon Framework 4 wrapped these in a {@code DomainEventMessage} carrying the aggregate identifier, a synthesized
     * aggregate type and a sequence number. Axon Framework 5 has no such message, so the three fields travel as
     * {@link LegacyAggregateEnvelope} instead and reach a Saga handler as
     * {@link org.axonframework.messaging.core.annotation.SourceId SourceId},
     * {@link org.axonframework.messaging.core.annotation.AggregateType AggregateType} and
     * {@link org.axonframework.messaging.eventhandling.annotation.SequenceNumber SequenceNumber} parameters.
     * <p>
     * The aggregate type is synthesized as {@code Stub_} followed by the aggregate identifier, and sequence numbers
     * start at zero and count up per aggregate, both as in Axon Framework 4.
     */
    private class AggregateEventPublisher implements GivenAggregateEventPublisher, WhenAggregateEventPublisher {

        private final String aggregateIdentifier;
        private final String aggregateType;
        private long sequenceNumber = 0;

        private AggregateEventPublisher(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
            this.aggregateType = "Stub_" + aggregateIdentifier;
        }

        @Override
        public ContinuedGivenState published(Object... events) {
            for (Object event : events) {
                givenAPublished(event, aggregateMetadata(Map.of()));
            }
            return SagaTestFixture.this;
        }

        @Override
        public FixtureExecutionResult publishes(Object event) {
            return publishes(event, Map.of());
        }

        @Override
        public FixtureExecutionResult publishes(Object event, Map<String, String> metadata) {
            return publishInWhen(when(), event, aggregateMetadata(metadata));
        }

        private Map<String, String> aggregateMetadata(Map<String, String> metadata) {
            return LegacyAggregateEnvelope.withAggregateFields(metadata,
                                                               aggregateType,
                                                               aggregateIdentifier,
                                                               sequenceNumber++);
        }
    }
}
