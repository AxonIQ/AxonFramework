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
import org.axonframework.messaging.commandhandling.CommandPriorityCalculator;
import org.axonframework.messaging.commandhandling.annotation.AnnotationRoutingStrategy;
import org.axonframework.messaging.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericDomainEventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.TrackedEventMessage;
import org.axonframework.messaging.eventhandling.interception.EventMessageHandlerInterceptorChain;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ListenerInvocationErrorHandler;
import org.axonframework.messaging.eventhandling.processing.errorhandling.LoggingErrorHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.ResultMessage;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathHandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.SimpleResourceParameterResolverFactory;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;
import org.axonframework.modelling.saga.AnnotatedSagaManager;
import org.axonframework.modelling.saga.ResourceInjector;
import org.axonframework.modelling.saga.SagaRepository;
import org.axonframework.modelling.saga.SimpleResourceInjector;
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.axonframework.test.FixtureExecutionException;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.matchers.IgnoreField;
import org.axonframework.test.util.CallbackBehavior;
import org.axonframework.test.util.RecordingCommandBus;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;

import static java.lang.String.format;
import static org.axonframework.common.ReflectionUtils.fieldsOf;

/**
 * Fixture for testing Annotated Sagas based on events and time passing. This fixture allows resources to be configured
 * for the sagas to use.
 *
 * @author Allard Buijze
 * @since 1.1
 */
public class SagaTestFixture<T> implements FixtureConfiguration, ContinuedGivenState {

    private final RecordingCommandBus commandBus;
    private final EventBus eventBus;
    //    private final StubEventScheduler eventScheduler;
//    private final StubDeadlineManager deadlineManager;
    private final LinkedList<ParameterResolverFactory> registeredParameterResolverFactories = new LinkedList<>();
    private final LinkedList<HandlerDefinition> registeredHandlerDefinitions = new LinkedList<>();
    private final LinkedList<HandlerEnhancerDefinition> registeredHandlerEnhancerDefinitions = new LinkedList<>();
    private final LinkedList<MessageHandlerInterceptor<? super EventMessage>> eventHandlerInterceptors = new LinkedList<>();
    private final RecordingListenerInvocationErrorHandler recordingListenerInvocationErrorHandler;

    private final Class<T> sagaType;
    private final InMemorySagaStore sagaStore;
    private AnnotatedSagaManager<T> sagaManager;
    private final LinkedList<Object> registeredResources = new LinkedList<>();
    private ResourceInjector resourceInjector;

    private final FixtureExecutionResultImpl<T> fixtureExecutionResult;
    private final Map<Object, AggregateEventPublisherImpl> aggregatePublishers = new HashMap<>();
    private final MutableFieldFilter fieldFilters = new MutableFieldFilter();

    private boolean transienceCheckEnabled = true;
    private boolean resourcesInitialized = false;
    private final AtomicLong globalSequence = new AtomicLong();

    /**
     * Creates an instance of the AnnotatedSagaTestFixture to test sagas of the given {@code sagaType}.
     *
     * @param sagaType The type of saga under test
     */
    public SagaTestFixture(Class<T> sagaType) {
        commandBus = new RecordingCommandBus();
        eventBus = new SimpleEventBus();
//        eventScheduler = new StubEventScheduler();
//        deadlineManager = new StubDeadlineManager();
        registeredParameterResolverFactories.add(new SimpleResourceParameterResolverFactory(registeredResources));
        registeredParameterResolverFactories.add(ClasspathParameterResolverFactory.forClass(sagaType));
        registeredHandlerDefinitions.add(ClasspathHandlerDefinition.forClass(sagaType));
        registeredHandlerEnhancerDefinitions.add(ClasspathHandlerEnhancerDefinition.forClass(sagaType));
        recordingListenerInvocationErrorHandler = new RecordingListenerInvocationErrorHandler(new LoggingErrorHandler());

        this.sagaType = sagaType;
        sagaStore = new InMemorySagaStore();

        registeredResources.add(eventBus);
        registeredResources.add(commandBus);
//        registeredResources.add(eventScheduler);
//        registeredResources.add(deadlineManager);
        registeredResources.add(new DefaultCommandGateway(
                commandBus,
                new ClassBasedMessageTypeResolver(),
                CommandPriorityCalculator.defaultCalculator(),
                new AnnotationRoutingStrategy()
        ));

        fixtureExecutionResult = new FixtureExecutionResultImpl<>(
                sagaStore,
//                eventScheduler,
//                deadlineManager,
                eventBus,
                commandBus,
                sagaType,
                fieldFilters,
                recordingListenerInvocationErrorHandler);
    }

    /**
     * Handles the given {@code event} in the scope of a Unit of Work. If handling the event results in an exception the
     * exception will be wrapped in a {@link FixtureExecutionException}.
     *
     * @param event The event message to handle
     */
    protected void handleInSaga(EventMessage event) {
        ensureSagaResourcesInitialized();
        TrackedEventMessage trackedEventMessage = asTrackedEventMessage(event);
        LegacyDefaultUnitOfWork<? extends EventMessage> unitOfWork =
                LegacyDefaultUnitOfWork.startAndGet(trackedEventMessage);

        /*
        ResultMessage<?> resultMessage = unitOfWork.executeWithResult(
                (context) -> {
                    sagaManager.handle(unitOfWork.getMessage(), context, Segment.ROOT_SEGMENT);
                    return unitOfWork.getExecutionResult();
                }
        );
         */
        // TODO reintegrate with #3097
        ResultMessage resultMessage = unitOfWork.executeWithResult(
                (context) -> new EventMessageHandlerInterceptorChain(
                        eventHandlerInterceptors,
                        (m, ctx) -> {
                            try {
                                sagaManager.handle(m, ctx, Segment.ROOT_SEGMENT);
                                return MessageStream.empty();
                            } catch (Throwable t) {
                                return MessageStream.failed(t);
                            }
                        }
                ).proceed(unitOfWork.getMessage(), context)
        );

        if (resultMessage.payload() instanceof Throwable) {
            Throwable e = resultMessage.payloadAs(Throwable.class);
            if (e instanceof Error error) {
                throw error;
            }
            throw new FixtureExecutionException("Exception occurred while handling an event", e);
        }
    }

    private TrackedEventMessage asTrackedEventMessage(EventMessage event) {
        return null;
//        return EventUtils.asTrackedEventMessage(
//                event, new GlobalSequenceTrackingToken(globalSequence.getAndIncrement()));
    }

    /**
     * Handles the given {@code deadlineMessage} in the saga described by the given {@code sagaDescriptor}. Deadline
     * message is handled in the scope of a {@link LegacyUnitOfWork}. If handling the deadline results in an exception,
     * the exception will be wrapped in a {@link FixtureExecutionException}.
     *
     * @param sagaDescriptor  A {@link ScopeDescriptor} describing the saga under test
     * @param deadlineMessage The {@code DeadlineMessage} to be handled
     */
//    protected void handleDeadline(ScopeDescriptor sagaDescriptor, DeadlineMessage deadlineMessage) throws Exception {
//        ensureSagaResourcesInitialized();
//        sagaManager.send(deadlineMessage, new LegacyMessageSupportingContext(deadlineMessage), sagaDescriptor);
//    }

    /**
     * Initializes the saga resources if it hasn't already done so. If once initialized, this method does nothing.
     */
    protected void ensureSagaResourcesInitialized() {
        if (!resourcesInitialized) {
            SagaRepository<T> sagaRepository = AnnotatedSagaRepository.<T>builder()
                                                                      .sagaType(sagaType)
                                                                      .parameterResolverFactory(
                                                                              getParameterResolverFactory())
                                                                      .handlerDefinition(getHandlerDefinition())
                                                                      .sagaStore(sagaStore)
                                                                      .resourceInjector(getResourceInjector())
                                                                      .build();
            sagaManager = AnnotatedSagaManager.<T>builder()
                                              .sagaRepository(sagaRepository)
                                              .sagaType(sagaType)
                                              .parameterResolverFactory(getParameterResolverFactory())
                                              .handlerDefinition(getHandlerDefinition())
                                              .listenerInvocationErrorHandler(recordingListenerInvocationErrorHandler)
                                              .build();
            resourcesInitialized = true;
        }
    }

    private ParameterResolverFactory getParameterResolverFactory() {
        return MultiParameterResolverFactory.ordered(registeredParameterResolverFactories);
    }

    private HandlerDefinition getHandlerDefinition() {
        HandlerEnhancerDefinition handlerEnhancerDefinition =
                MultiHandlerEnhancerDefinition.ordered(registeredHandlerEnhancerDefinitions);
        return MultiHandlerDefinition.ordered(registeredHandlerDefinitions, handlerEnhancerDefinition);
    }

    private ResourceInjector getResourceInjector() {
        TransienceValidatingResourceInjector defaultResourceInjector =
                new TransienceValidatingResourceInjector(registeredResources, transienceCheckEnabled);
        return resourceInjector != null
                ? new WrappingResourceInjector(resourceInjector, defaultResourceInjector)
                : defaultResourceInjector;
    }

    @Override
    public FixtureConfiguration withTransienceCheckDisabled() {
        this.transienceCheckEnabled = false;
        return this;
    }

    @Override
    public FixtureExecutionResult whenTimeElapses(Duration elapsedTime) {
        try {
            fixtureExecutionResult.startRecording();
//            eventScheduler.advanceTimeBy(elapsedTime, this::handleInSaga);
//            deadlineManager.advanceTimeBy(elapsedTime, this::handleDeadline);
        } catch (Exception e) {
            throw new FixtureExecutionException("Exception occurred while trying to advance time " +
                                                        "and handle scheduled events", e);
        }
        return fixtureExecutionResult;
    }

    @Override
    public FixtureExecutionResult whenTimeAdvancesTo(Instant newDateTime) {
        try {
            fixtureExecutionResult.startRecording();
//            eventScheduler.advanceTimeTo(newDateTime, this::handleInSaga);
//            deadlineManager.advanceTimeTo(newDateTime, this::handleDeadline);
        } catch (Exception e) {
            throw new FixtureExecutionException("Exception occurred while trying to advance time " +
                                                        "and handle scheduled events", e);
        }

        return fixtureExecutionResult;
    }

    @Override
    public void registerResource(Object resource) {
        registeredResources.addFirst(resource);
    }

    @Override
    public FixtureConfiguration registerParameterResolverFactory(ParameterResolverFactory parameterResolverFactory) {
        this.registeredParameterResolverFactories.addFirst(parameterResolverFactory);
        return this;
    }

    @Override
    public void setCallbackBehavior(CallbackBehavior callbackBehavior) {
        commandBus.setCallbackBehavior(callbackBehavior);
    }

    @Override
    public GivenAggregateEventPublisher givenAggregate(String aggregateIdentifier) {
        return getPublisherFor(aggregateIdentifier);
    }

    @Override
    public ContinuedGivenState givenAPublished(Object event) {
        handleInSaga(timeCorrectedEventMessage(event));
        return this;
    }

    @SuppressWarnings("unchecked")
    private static <P> EventMessage asEventMessage(Object event) {
        if (event instanceof EventMessage) {
            return (EventMessage) event;
        } else if (event instanceof Message message) {
            return new GenericEventMessage(message);
        }
        return new GenericEventMessage(
                new GenericMessage(new MessageType(event.getClass()), event)
        );
    }

    @Override
    public ContinuedGivenState givenCurrentTime(Instant currentTime) {
//        eventScheduler.initializeAt(currentTime);
//        deadlineManager.initializeAt(currentTime);
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
    public ContinuedGivenState andThenTimeElapses(final Duration elapsedTime) {
//        eventScheduler.advanceTimeBy(elapsedTime, this::handleInSaga);
//        deadlineManager.advanceTimeBy(elapsedTime, this::handleDeadline);
        return this;
    }

    @Override
    public ContinuedGivenState andThenTimeAdvancesTo(final Instant newDateTime) {
//        eventScheduler.advanceTimeTo(newDateTime, this::handleInSaga);
//        deadlineManager.advanceTimeTo(newDateTime, this::handleDeadline);
        return this;
    }

    @Override
    public ContinuedGivenState andThenAPublished(Object event) {
        handleInSaga(timeCorrectedEventMessage(event));
        return this;
    }

    @Override
    public ContinuedGivenState givenAPublished(Object event, Map<String, String> metadata) {
        EventMessage msg = asEventMessage(event).andMetadata(metadata);
        handleInSaga(timeCorrectedEventMessage(msg));
        return this;
    }

    @Override
    public WhenAggregateEventPublisher whenAggregate(String aggregateIdentifier) {
        fixtureExecutionResult.startRecording();
        return getPublisherFor(aggregateIdentifier);
    }

    @Override
    public FixtureExecutionResult whenPublishingA(Object event) {
        fixtureExecutionResult.startRecording();
        handleInSaga(timeCorrectedEventMessage(event));
        return fixtureExecutionResult;
    }

    @Override
    public ContinuedGivenState andThenAPublished(Object event, Map<String, String> metadata) {
        EventMessage msg = asEventMessage(event).andMetadata(metadata);

        handleInSaga(timeCorrectedEventMessage(msg));
        return this;
    }

    @Override
    public FixtureExecutionResult whenPublishingA(Object event, Map<String, String> metadata) {
        EventMessage msg = asEventMessage(event).andMetadata(metadata);

        fixtureExecutionResult.startRecording();
        handleInSaga(timeCorrectedEventMessage(msg));
        return fixtureExecutionResult;
    }

    private EventMessage timeCorrectedEventMessage(Object event) {
        EventMessage msg = asEventMessage(event);
        return new GenericEventMessage(
                msg.identifier(), msg.type(), msg.payload(), msg.metadata(), currentTime()
        );
    }

    @Override
    public Instant currentTime() {
        return Instant.now(); //eventScheduler.getCurrentDateTime();
    }

    @Override
    public FixtureConfiguration registerFieldFilter(FieldFilter fieldFilter) {
        this.fieldFilters.add(fieldFilter);
        return this;
    }

    @Override
    public FixtureConfiguration registerIgnoredField(Class<?> declaringClass, String fieldName) {
        return registerFieldFilter(new IgnoreField(declaringClass, fieldName));
    }

    @Override
    public FixtureConfiguration registerHandlerDefinition(HandlerDefinition handlerDefinition) {
        this.registeredHandlerDefinitions.addFirst(handlerDefinition);
        return this;
    }

    @Override
    public FixtureConfiguration registerHandlerEnhancerDefinition(HandlerEnhancerDefinition handlerEnhancerDefinition) {
        this.registeredHandlerEnhancerDefinitions.addFirst(handlerEnhancerDefinition);
        return this;
    }

//    @Override
//    public FixtureConfiguration registerDeadlineDispatchInterceptor(
//            MessageDispatchInterceptor<? super DeadlineMessage> deadlineDispatchInterceptor
//    ) {
//        this.deadlineManager.registerDispatchInterceptor(deadlineDispatchInterceptor);
//        return this;
//    }

//    @Override
//    public FixtureConfiguration registerDeadlineHandlerInterceptor(
//            MessageHandlerInterceptor<DeadlineMessage> deadlineHandlerInterceptor
//    ) {
//        this.deadlineManager.registerHandlerInterceptor(deadlineHandlerInterceptor);
//        return this;
//    }

    @Override
    public FixtureConfiguration registerEventHandlerInterceptor(
            MessageHandlerInterceptor<EventMessage> eventHandlerInterceptor
    ) {
        this.eventHandlerInterceptors.add(eventHandlerInterceptor);
        return this;
    }

    @Override
    public FixtureConfiguration registerStartRecordingCallback(Runnable onStartRecordingCallback) {
        this.fixtureExecutionResult.registerStartRecordingCallback(onStartRecordingCallback);
        return this;
    }

    @Override
    public FixtureConfiguration registerListenerInvocationErrorHandler(
            ListenerInvocationErrorHandler listenerInvocationErrorHandler) {
        recordingListenerInvocationErrorHandler.setListenerInvocationErrorHandler(listenerInvocationErrorHandler);
        return this;
    }

    @Override
    public FixtureConfiguration suppressExceptionInGivenPhase(boolean suppress) {
        recordingListenerInvocationErrorHandler.failOnErrorInPreparation(!suppress);
        return this;
    }

    @Override
    public FixtureConfiguration registerResourceInjector(ResourceInjector resourceInjector) {
        this.resourceInjector = resourceInjector;
        return this;
    }

    private AggregateEventPublisherImpl getPublisherFor(String aggregateIdentifier) {
        if (!aggregatePublishers.containsKey(aggregateIdentifier)) {
            aggregatePublishers.put(aggregateIdentifier, new AggregateEventPublisherImpl(aggregateIdentifier));
        }
        return aggregatePublishers.get(aggregateIdentifier);
    }

    @Override
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public RecordingCommandBus getCommandBus() {
        return commandBus;
    }

    private class AggregateEventPublisherImpl implements GivenAggregateEventPublisher, WhenAggregateEventPublisher {

        private final String aggregateIdentifier;
        private final String aggregateType;
        private int sequenceNumber = 0;

        public AggregateEventPublisherImpl(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
            this.aggregateType = "Stub_" + aggregateIdentifier;
        }

        @Override
        public ContinuedGivenState published(Object... events) {
            publish(events);
            return SagaTestFixture.this;
        }

        @Override
        public FixtureExecutionResult publishes(Object event) {
            publish(event);
            return fixtureExecutionResult;
        }

        @Override
        public FixtureExecutionResult publishes(Object event, Map<String, String> metadata) {
            EventMessage eventMessage = asEventMessage(event).andMetadata(metadata);
            publish(eventMessage);

            return fixtureExecutionResult;
        }

        private void publish(Object... events) {
            for (Object event : events) {
                EventMessage eventMessage = asEventMessage(event);
                handleInSaga(new GenericDomainEventMessage(aggregateType,
                                                           aggregateIdentifier,
                                                           sequenceNumber++,
                                                           eventMessage.identifier(),
                                                           eventMessage.type(),
                                                           eventMessage.payload(),
                                                           eventMessage.metadata(),
                                                           currentTime()));
            }
        }
    }

    private static class MutableFieldFilter implements FieldFilter {

        private final List<FieldFilter> filters = new ArrayList<>();

        @Override
        public boolean accept(Field field) {
            for (FieldFilter filter : filters) {
                if (!filter.accept(field)) {
                    return false;
                }
            }
            return true;
        }

        public void add(FieldFilter fieldFilter) {
            filters.add(fieldFilter);
        }
    }

    private static class TransienceValidatingResourceInjector extends SimpleResourceInjector {

        private final List<Object> registeredResources;
        private final boolean transienceCheckEnabled;

        public TransienceValidatingResourceInjector(List<Object> registeredResources, boolean transienceCheckEnabled) {
            super(registeredResources);
            this.registeredResources = registeredResources;
            this.transienceCheckEnabled = transienceCheckEnabled;
        }

        @Override
        public void injectResources(Object saga) {
            super.injectResources(saga);
            if (transienceCheckEnabled) {
                StreamSupport.stream(fieldsOf(saga.getClass()).spliterator(), false)
                             .filter(f -> !Modifier.isTransient(f.getModifiers()))
                             .filter(f -> registeredResources.contains(ReflectionUtils.getFieldValue(f, saga)))
                             .findFirst()
                             .ifPresent(field -> {
                                 throw new AssertionError(format(
                                         """
                                                 Field %s.%s is injected with a resource,\
                                                  but it doesn't have the 'transient' modifier.\
                                                 
                                                 Mark field as 'transient' or disable this check using:\
                                                 
                                                 fixture.withTransienceCheckDisabled()""",
                                         field.getDeclaringClass(),
                                         field.getName()
                                 ));
                             });
            }
        }
    }

    /**
         * Wrapping {@link ResourceInjector} instance. Will first call the {@link TransienceValidatingResourceInjector}, to
         * ensure the fixture's approach of injecting the default classes (like the {@link EventBus} and {@link CommandBus}
         * for example) is maintained. Afterward, the custom {@code ResourceInjector} provided through the
         * {@link #registerResourceInjector(ResourceInjector)} is called. This will (depending on the implementation) inject
         * more resources, as well as potentially override resources already injected by the
         * {@code TransienceValidatingResourceInjector}.
         */
        private record WrappingResourceInjector(ResourceInjector customResourceInjector,
                                                TransienceValidatingResourceInjector defaultResourceInjector)
            implements ResourceInjector {

        @Override
            public void injectResources(Object saga) {
                // First call the default, transience checking injector to ensure correct fixture workings
                defaultResourceInjector.injectResources(saga);
                // Then, call the custom injector, to add other resource or override injection by the default injector
                customResourceInjector.injectResources(saga);
            }
        }
}
