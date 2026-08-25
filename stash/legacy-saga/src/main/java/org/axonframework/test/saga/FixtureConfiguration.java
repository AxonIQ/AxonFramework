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
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ListenerInvocationErrorHandler;
import org.axonframework.messaging.eventhandling.processing.errorhandling.LoggingErrorHandler;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathHandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.SimpleResourceParameterResolverFactory;
import org.axonframework.modelling.saga.ResourceInjector;
import org.axonframework.test.FixtureExecutionException;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.util.CallbackBehavior;

import java.time.Instant;
import java.util.Map;


/**
 * Interface describing action to perform on a Saga Test Fixture during the configuration phase.
 *
 * @author Allard Buijze
 * @since 1.1
 */
public interface FixtureConfiguration {

    /**
     * Disables the check that injected resources are stored in fields that are marked 'transient'.
     * <p>
     * By default, Saga fixtures check for the transient modifier on fields that hold injected resources. These
     * resources are generally not means to be serialized as part of the Saga.
     * <p>
     * When the transience check reports false positives, this method allows this check to be skipped.
     *
     * @return this instance for fluent interfacing.
     */
    FixtureConfiguration withTransienceCheckDisabled();

    /**
     * Registers the given {@code resource}. When a Saga is created, all resources are injected on that instance before
     * any Events are passed onto it.
     * <p/>
     * Note that a CommandBus, EventBus and EventScheduler are already registered as resources, and need not be
     * registered again.
     * <p/>
     * Also note that you might need to reset the resources manually if you want to isolate behavior during the "when"
     * stage of the test.
     *
     * @param resource the resource to register.
     */
    void registerResource(Object resource);

    /**
     * Registers a {@link ParameterResolverFactory} within this fixture. The given {@code parameterResolverFactory} will
     * be added to the other parameter resolver factories introduced through
     * {@link ClasspathParameterResolverFactory#forClass(Class)} and the {@link SimpleResourceParameterResolverFactory}
     * adding the registered resources (with {@link #registerResource(Object)}. The type of the saga under test is used
     * as input for the {@code ClasspathParameterResolverFactory#forClass(Class)} operation.
     *
     * @param parameterResolverFactory the {@link ParameterResolver} to register within this fixture
     * @return the current FixtureConfiguration, for fluent interfacing
     * @see #registerResource(Object)
     */
    FixtureConfiguration registerParameterResolverFactory(ParameterResolverFactory parameterResolverFactory);

    /**
     * Registers the given {@code fieldFilter}, which is used to define which Fields are used when comparing objects.
     * The {@code ResultValidator#expectEvents(Object...)} and
     * {@code ResultValidator#expectResultMessage(CommandResultMessage)}, for example, use this filter.
     * <p/>
     * When multiple filters are registered, a Field must be accepted by all registered filters in order to be
     * accepted.
     * <p/>
     * By default, all Fields are included in the comparison.
     *
     * @param fieldFilter The FieldFilter that defines which fields to include in the comparison
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerFieldFilter(FieldFilter fieldFilter);

    /**
     * Indicates that a field with given {@code fieldName}, which is declared in given {@code declaringClass} is ignored
     * when performing deep equality checks.
     *
     * @param declaringClass The class declaring the field
     * @param fieldName      The name of the field
     * @return the current FixtureConfiguration, for fluent interfacing
     * @throws FixtureExecutionException when no such field is declared
     */
    FixtureConfiguration registerIgnoredField(Class<?> declaringClass, String fieldName);

    /**
     * Registers a {@link HandlerDefinition} within this fixture. The given {@code handlerDefinition} is added to the
     * handler definitions introduced through {@link ClasspathHandlerDefinition#forClass(Class)}. The type of the saga
     * under test is used as input for the {@code ClasspathHandlerDefinition#forClass(Class)} operation.
     *
     * @param handlerDefinition used to create concrete handlers
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerHandlerDefinition(HandlerDefinition handlerDefinition);

    /**
     * Registers a {@link HandlerEnhancerDefinition} within this fixture. This given {@code handlerEnhancerDefinition}
     * is added to the handler enhancer definitions introduced through
     * {@link ClasspathHandlerEnhancerDefinition#forClass(Class)}. The type of the saga under test is used as input for
     * the {@code ClasspathHandlerEnhancerDefinition#forClass(Class)} operation.
     *
     * @param handlerEnhancerDefinition the {@link HandlerEnhancerDefinition} to register within this fixture
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerHandlerEnhancerDefinition(HandlerEnhancerDefinition handlerEnhancerDefinition);

    /**
     * Registers a deadline dispatch interceptor which will always be invoked before a deadline is dispatched
     * (scheduled) on the {@link org.axonframework.deadline.DeadlineManager} to perform a task specified in the
     * interceptor.
     *
     * @param deadlineDispatchInterceptor the interceptor for dispatching (scheduling) deadlines
     * @return the current FixtureConfiguration, for fluent interfacing
     */
//    FixtureConfiguration registerDeadlineDispatchInterceptor(
//            MessageDispatchInterceptor<? super DeadlineMessage> deadlineDispatchInterceptor
//    );

    /**
     * Registers a deadline handler interceptor which will always be invoked before a deadline is handled to perform a
     * task specified in the interceptor.
     *
     * @param deadlineHandlerInterceptor the interceptor for handling deadlines
     * @return the current FixtureConfiguration, for fluent interfacing
     */
//    FixtureConfiguration registerDeadlineHandlerInterceptor(
//            MessageHandlerInterceptor<DeadlineMessage> deadlineHandlerInterceptor
//    );

    /**
     * Registers a {@link MessageHandlerInterceptor} for {@link EventMessage EventMessages}.
     * <p>
     * Will always be invoked before an event is handled to perform a task specified in the interceptor. Interceptors
     * are invoked in the order they have been registered in.
     *
     * @param eventHandlerInterceptor the interceptor for handling {@link EventMessage EventMessages}
     * @return The current {@link FixtureConfiguration}, for fluent interfacing.
     */
    default FixtureConfiguration registerEventHandlerInterceptor(
            MessageHandlerInterceptor<EventMessage> eventHandlerInterceptor
    ) {
        throw new UnsupportedOperationException(
                "The FixtureConfiguration implementation does not support this operation"
        );
    }

    /**
     * Registers a callback to be invoked when the fixture execution starts recording. This happens right before
     * invocation of the 'when' step (stimulus) of the fixture.
     * <p/>
     * Use this to manage Saga dependencies which are not an Axon first class citizen, but do require monitoring of
     * their interactions. For example, register the callback to set a mock in recording mode.
     *
     * @param onStartRecordingCallback callback to invoke
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerStartRecordingCallback(Runnable onStartRecordingCallback);

    /**
     * Registers a {@link ListenerInvocationErrorHandler} to be set for the Saga to deal with exceptions being thrown
     * from within Saga Event Handlers. Will be given to the
     * {@link org.axonframework.modelling.saga.AnnotatedSagaManager} for the defined Saga type. Defaults to a
     * {@link LoggingErrorHandler} wrapped inside a {@link RecordingListenerInvocationErrorHandler}.
     *
     * @param listenerInvocationErrorHandler to be set for the Saga to deal with exceptions being thrown from within
     *                                       Saga Event Handlers
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerListenerInvocationErrorHandler(
            ListenerInvocationErrorHandler listenerInvocationErrorHandler);

    /**
     * Configure whether the fixture should suppress exceptions thrown during the given-phase. When {@code suppress} is
     * {@code true}, the fixture moves on to the when-phase regardless of any exceptions thrown during the given-phase.
     * <p>
     * Note that setting this to {@code true} means the
     * {@link #registerListenerInvocationErrorHandler(ListenerInvocationErrorHandler) registered}
     * {@link ListenerInvocationErrorHandler} is not invoked during exception in the given-phase. Defaults to
     * suppressing during given-phase exceptions.
     *
     * @param suppress A {@code boolean} describing whether the fixture should suppress failures during the
     *                 given-phase.
     * @return The current fixture, for fluent interfacing.
     */
    default FixtureConfiguration suppressExceptionInGivenPhase(boolean suppress) {
        return this;
    }

    /**
     * Registers a {@link ResourceInjector} within this fixture. This approach can be used if a custom
     * {@code ResourceInjector} has been built for a project which the user wants to take into account when testing it's
     * sagas.
     * <p>
     * The provided {@code resourceInjector} will be paired with the fixture's default {@code ResourceInjector} to keep
     * support for the {@link #registerResource(Object)} and {@link #withTransienceCheckDisabled()} methods. Note that
     * <b>first</b> the default injector is called, and after that the given {@code resourceInjector}. This approach
     * ensures the fixture's correct workings for default provided resources, like the {@link EventBus} and
     * {@link CommandBus}}, whilst allowing the capability to append and/or override with the given
     * {@code resourceInjector}.
     * <p>
     * Care should be taken if the custom {@code resourceInjector} overrides default resources like the {@code EventBus}
     * and {@code CommandBus}, as the fixture uses specialized versions of the default sources to support all testing
     * functionality.
     *
     * @param resourceInjector the {@link ResourceInjector} to register within this fixture
     * @return the current FixtureConfiguration, for fluent interfacing
     */
    FixtureConfiguration registerResourceInjector(ResourceInjector resourceInjector);

    /**
     * Sets the instance that defines the behavior of the Command Bus when a command is dispatched with a callback.
     *
     * @param callbackBehavior The instance deciding to how the callback should be invoked.
     */
    void setCallbackBehavior(CallbackBehavior callbackBehavior);

    /**
     * Use this method to indicate that an aggregate with given identifier published certain events.
     * <p/>
     * Can be chained to build natural sentences:<br/> {@code andThenAggregate(someIdentifier).published(someEvents)}
     *
     * @param aggregateIdentifier The identifier of the aggregate the events should appear to come from
     * @return an object that allows registration of the actual events to send
     */
    GivenAggregateEventPublisher givenAggregate(String aggregateIdentifier);

    /**
     * Use this method to indicate a specific moment as the initial current time "known" by the fixture at the start of
     * the given state.
     *
     * @param currentTime The simulated "current time" at which the given state is initialized
     * @return an object that allows chaining of more given state
     */
    ContinuedGivenState givenCurrentTime(Instant currentTime);

    /**
     * Indicates that the given {@code applicationEvent} has been published in the past. This event is sent to the
     * associated sagas.
     *
     * @param event The event to publish
     * @return an object that allows chaining of more given state
     */
    ContinuedGivenState givenAPublished(Object event);


    /**
     * Indicates that the given {@code event} with given {@code metadata} has been published in the past. This event is
     * sent to the associated sagas.
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
     * @since 2.1.1
     */
    WhenState givenNoPriorActivity();

    /**
     * Returns the time as "known" by the fixture. This is the time at which the fixture was created, plus the amount of
     * time the fixture was told to simulate a "wait".
     * <p/>
     * This time can be used to predict calculations that the saga may have made based on timestamps from the events it
     * received.
     *
     * @return the simulated "current time" of the fixture.
     */
    Instant currentTime();

    /**
     * Returns the event bus used by this fixture. The event bus is provided for wiring purposes only, for example to
     * allow command handlers to publish events other than Domain Events. Events published on the returned event bus are
     * recorded an evaluated in the {@code ResultValidator} operations.
     *
     * @return the event bus used by this fixture
     */
    EventBus getEventBus();

    /**
     * Returns the command bus used by this fixture. The command bus is provided for wiring purposes only, for example
     * to support composite commands (a single command that causes the execution of one or more others).
     *
     * @return the command bus used by this fixture
     */
    CommandBus getCommandBus();
}
