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
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.util.CallbackBehavior;
import org.axonframework.test.util.DefaultCallbackBehavior;

import java.time.Instant;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Interface describing action to perform on a {@link SagaTestFixture} during the configuration phase.
 * <p>
 * Most of what an Axon Framework 4 fixture configured is now expressible on the {@link MessagingConfigurer} an
 * application configures itself, which {@link #customize(UnaryOperator)} hands to the caller. Only the settings that
 * configurer cannot express are kept here. Three Axon Framework 4 methods have no counterpart at all:
 * <ul>
 *     <li>{@code registerResourceInjector(..)}: a Saga has no injected fields to configure, since collaborators
 *     reach it as handler parameters.</li>
 *     <li>{@code registerListenerInvocationErrorHandler(..)}: Axon Framework 5 has no such component, and building
 *     one would mean porting two more that a Saga manager has nothing to fill in. A Saga suppresses its own failures
 *     with an
 *     {@link org.axonframework.messaging.core.interception.annotation.ExceptionHandler ExceptionHandler} method,
 *     which is what the Axon Framework 4 default behaviour became.</li>
 *     <li>{@code registerCommandGateway(..)}: Axon Framework 5 removed the {@code CommandGatewayFactory}, so a
 *     custom gateway interface can no longer be built anywhere, test or production. A Saga sends commands through
 *     {@link org.axonframework.messaging.commandhandling.gateway.CommandDispatcher CommandDispatcher} or
 *     {@link org.axonframework.messaging.commandhandling.gateway.CommandGateway CommandGateway} as a handler
 *     parameter, and a test decides the answer by subscribing a handler through {@link #customize(UnaryOperator)}
 *     or by setting a {@link #setCallbackBehavior(CallbackBehavior) callback behaviour}.</li>
 * </ul>
 * The two deadline interceptor registrations are absent for a different reason, noted where they would sit.
 * Configuration is applied while building the fixture, which happens when the first event is handled or a bus is
 * requested. Merely selecting a given aggregate or declaring that there is no prior activity does not build it.
 * Anything registered after the fixture is built is ignored, as it was in Axon Framework 4.
 *
 * @author Allard Buijze
 * @since 1.1
 */
public interface FixtureConfiguration {

    /**
     * Does nothing, and is kept only so an Axon Framework 4 test suite still compiles.
     * <p>
     * Axon Framework 4 refused a Saga holding an injected resource in a field that was not {@code transient}, because
     * the Saga is serialized into the {@link org.axonframework.modelling.saga.repository.SagaStore SagaStore} and the
     * resource would go with it. This method turned that check off.
     * <p>
     * Axon Framework 5 has no such check, and rebuilding it would not help. It worked by comparing each field against
     * the resource injector's list, and there is no injector; and its advice no longer applies, since {@code transient}
     * is a Java serialization marker while a Saga is now serialized through a
     * {@link org.axonframework.conversion.Converter Converter}. A Saga can still hold infrastructure in a field, but
     * nothing puts it there on the Saga's behalf, and serializing it fails loudly.
     *
     * @return the current FixtureConfiguration, for fluent interfacing
     * @deprecated There is no transience check to disable. Delete the call.
     */
    @Deprecated(forRemoval = true)
    FixtureConfiguration withTransienceCheckDisabled();

    /**
     * Customizes the {@link MessagingConfigurer} the fixture builds the Saga on, for anything this interface does not
     * expose.
     * <p>
     * The Saga, its store and its event processor are registered before the given {@code customization} is applied, so
     * it can add to them or replace what they registered.
     *
     * @param customization the customization to apply to the configurer
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration customize(UnaryOperator<MessagingConfigurer> customization);

    /**
     * Registers the given {@code resource}, making it available to Saga handler methods declaring a parameter of an
     * assignable type.
     * <p>
     * Registering two resources of the same type makes the last one win, as it did in Axon Framework 4. Unlike Axon
     * Framework 4, the resource is not injected into the Saga's fields.
     *
     * @param resource the resource to make available to the Saga's handler methods
     */
    void registerResource(Object resource);


    /**
     * Registers the given {@code parameterResolverFactory}, used to resolve the parameters of the Saga's handler
     * methods.
     *
     * @param parameterResolverFactory the factory resolving handler method parameters
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerParameterResolverFactory(ParameterResolverFactory parameterResolverFactory);

    /**
     * Registers the given {@code handlerDefinition}, used to create the Saga's handlers.
     *
     * @param handlerDefinition the definition creating the Saga's handlers
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerHandlerDefinition(HandlerDefinition handlerDefinition);

    /**
     * Registers the given {@code handlerEnhancerDefinition}, used to enhance the Saga's handlers.
     *
     * @param handlerEnhancerDefinition the definition enhancing the Saga's handlers
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerHandlerEnhancerDefinition(HandlerEnhancerDefinition handlerEnhancerDefinition);

    /**
     * Registers the given {@code interceptor}, invoked around the Saga's event handling.
     * <p>
     * Interceptors are invoked in registration order, as they were in Axon Framework 4.
     *
     * @param interceptor the interceptor to invoke around the Saga's event handling
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerEventHandlerInterceptor(MessageHandlerInterceptor<? super EventMessage> interceptor);

    /**
     * Registers the given {@code fieldFilter}, defining which fields are compared when matching messages.
     *
     * @param fieldFilter the filter defining which fields to compare
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerFieldFilter(FieldFilter fieldFilter);

    /**
     * Indicates that a field with the given {@code fieldName}, declared in the given {@code declaringClass}, is ignored
     * when comparing messages.
     *
     * @param declaringClass the class declaring the field
     * @param fieldName      the name of the field to ignore
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerIgnoredField(Class<?> declaringClass, String fieldName);

    /**
     * Registers the given {@code callback}, invoked when the fixture starts recording what the Saga does, which is when
     * the "when" phase begins.
     *
     * @param callback the callback to invoke when recording starts
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerStartRecordingCallback(Runnable callback);

    /**
     * Sets the behaviour answering commands that no subscribed handler takes.
     * <p>
     * Defaults to a {@link DefaultCallbackBehavior}, which answers {@code null}, as Axon Framework 4 did. A command
     * that does reach a handler subscribed through {@link #customize(UnaryOperator)} is answered by that handler
     * instead.
     *
     * @param callbackBehavior the behaviour deciding what such a command returns
     */
    void setCallbackBehavior(CallbackBehavior callbackBehavior);

    /**
     * Sets whether a failure of a Saga handler during the "given" phase is suppressed rather than thrown.
     * <p>
     * Defaults to {@code false}, meaning a failure during the "given" phase surfaces. Axon Framework 4's
     * {@code FixtureConfiguration} documented the opposite default while its implementation did the same as this; the
     * implementation is what is kept.
     *
     * @param suppress whether to suppress a failure during the "given" phase
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration suppressExceptionInGivenPhase(boolean suppress);

    /**
     * The {@link EventBus} the fixture publishes on.
     * <p>
     * Axon Framework 5 splits an {@code EventBus} into a {@link EventSink} for publishing and a
     * {@link org.axonframework.messaging.core.SubscribableEventSource SubscribableEventSource} for subscribing. A test
     * only needs the publishing half, but the whole bus is returned so that
     * {@code fixture.getEventBus().subscribe(..)} keeps working, as it did in Axon Framework 4.
     * <p>
     * Axon Framework 5 obtains the bus from the application configuration. Calling this method therefore builds and
     * starts the fixture; make all fixture registrations before requesting the bus.
     *
     * @return the event bus the fixture publishes on
     */
    EventBus getEventBus();

    /**
     * The {@link CommandBus} the Saga dispatches on.
     * <p>
     * Axon Framework 5 obtains the bus from the application configuration. Calling this method therefore builds and
     * starts the fixture; make all fixture registrations before requesting the bus.
     *
     * @return the command bus the Saga dispatches on
     */
    CommandBus getCommandBus();

    /**
     * Use this method to indicate that an aggregate with given identifier published certain events.
     * <p>
     * Can be chained to build natural sentences: {@code givenAggregate(someIdentifier).published(someEvents)}
     *
     * @param aggregateIdentifier The identifier of the aggregate the events should appear to come from
     * @return an object that allows registration of the actual events to send
     */
    GivenAggregateEventPublisher givenAggregate(String aggregateIdentifier);

    /**
     * Indicates that the given {@code event} has been published in the past. This event is sent to the associated
     * Sagas.
     *
     * @param event The event to publish
     * @return an object that allows chaining of more given state
     */
    ContinuedGivenState givenAPublished(Object event);

    /**
     * Indicates that the given {@code event} with given {@code metadata} has been published in the past. This event is
     * sent to the associated Sagas.
     *
     * @param event    The event to publish
     * @param metadata The metadata to attach to the event
     * @return an object that allows chaining of more given state
     */
    ContinuedGivenState givenAPublished(Object event, Map<String, String> metadata);

    /**
     * Indicates that no relevant activity has occurred in the past.
     *
     * @return an object that allows the definition of the activity to measure Saga behavior
     */
    WhenState givenNoPriorActivity();

    /**
     * Sets the time the fixture starts from.
     *
     * @param currentTime The time to start the fixture at
     * @return an object that allows chaining of more given state
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    ContinuedGivenState givenCurrentTime(Instant currentTime);

    /**
     * The time as the fixture's scheduler sees it.
     *
     * @return the current time of the fixture's scheduler
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    Instant currentTime();

    // TODO #5006 - not declared at all are the two deadline interceptor registrations, because DeadlineMessage
    // itself is not ported:
    //
    //     FixtureConfiguration registerDeadlineDispatchInterceptor(MessageDispatchInterceptor<? super DeadlineMessage> interceptor);
    //     FixtureConfiguration registerDeadlineHandlerInterceptor(MessageHandlerInterceptor<? super DeadlineMessage> interceptor);
}
