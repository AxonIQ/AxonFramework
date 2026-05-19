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

package org.axonframework.test.aggregate;

import static java.lang.String.format;
import static org.axonframework.common.ReflectionUtils.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


import org.axonframework.common.ClockUtils;
import org.jspecify.annotations.Nullable;
import org.axonframework.common.Assert;
import org.axonframework.common.FutureUtils;
import org.axonframework.common.Registration;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.messaging.LegacyMessageHandler;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.commandhandling.annotation.AnnotatedCommandHandlingComponent;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
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
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.unitofwork.LegacyMessageSupportingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.DomainEventMessage;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericDomainEventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventsourcing.AggregateFactory;
import org.axonframework.messaging.eventsourcing.GenericAggregateFactory;
import org.axonframework.messaging.eventsourcing.LegacyEventSourcingRepository;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;
import org.axonframework.modelling.command.Aggregate;
import org.axonframework.modelling.command.AggregateAnnotationCommandHandler;
import org.axonframework.modelling.command.AggregateNotFoundException;
import org.axonframework.modelling.command.AggregateScopeDescriptor;
import org.axonframework.modelling.command.CommandTargetResolver;
import org.axonframework.modelling.command.Repository;
import org.axonframework.modelling.command.RepositoryProvider;
import org.axonframework.modelling.command.inspection.AggregateModel;
import org.axonframework.modelling.command.inspection.AnnotatedAggregate;
import org.axonframework.modelling.command.inspection.AnnotatedAggregateMetaModelFactory;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.FixtureExecutionException;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.matchers.IgnoreField;
import org.axonframework.test.matchers.MatchAllFieldFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A test fixture that allows the execution of given-when-then style test cases. For detailed usage information, see
 * {@link FixtureConfiguration}.
 *
 * @param <T> The type of Aggregate tested in this Fixture
 * @author Allard Buijze
 * @since 0.6
 * @deprecated In favor of the {@link org.axonframework.test.fixture.AxonTestFixture}.
 */
@Deprecated(since = "5.0.0", forRemoval = true)
public class AggregateTestFixture<T> implements FixtureConfiguration<T>, TestExecutor<T> {

    private static final Logger logger = LoggerFactory.getLogger(AggregateTestFixture.class);

    private final Class<T> aggregateType;
    private final Set<Class<? extends T>> subtypes = new HashSet<>();
    private final SimpleCommandBus commandBus;
    private final EventStore eventStore;
    private final List<FieldFilter> fieldFilters = new ArrayList<>();
    private final List<Object> resources = new ArrayList<>();
    private boolean useStateStorage;
    private RepositoryProvider repositoryProvider;
    private IdentifierValidatingRepository<T> repository;
    //    private final StubDeadlineManager deadlineManager;
    private String aggregateIdentifier;
    private Deque<DomainEventMessage> givenEvents;
    private Deque<DomainEventMessage> storedEvents;
    private List<EventMessage> publishedEvents;
    private long sequenceNumber;
    private boolean reportIllegalStateChange = true;
    private boolean explicitCommandHandlersSet;
    private final LinkedList<ParameterResolverFactory> registeredParameterResolverFactories = new LinkedList<>();
    private final LinkedList<HandlerDefinition> registeredHandlerDefinitions = new LinkedList<>();
    private final LinkedList<HandlerEnhancerDefinition> registeredHandlerEnhancerDefinitions = new LinkedList<>();
    private CommandTargetResolver commandTargetResolver;

    /**
     * Initializes a new given-when-then style test fixture for the given {@code aggregateType}.
     *
     * @param aggregateType the aggregate to initialize the test fixture for
     */
    public AggregateTestFixture(Class<T> aggregateType) {
//        deadlineManager = new StubDeadlineManager();
        commandBus = new SimpleCommandBus(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE));
        eventStore = new RecordingEventStore();
        resources.add(commandBus);
        resources.add(eventStore);
//        resources.add(deadlineManager);

        this.aggregateType = aggregateType;
        this.storedEvents = new LinkedList<>();
        this.publishedEvents = new ArrayList<>();
        this.givenEvents = new LinkedList<>();
        this.sequenceNumber = 0;

        registeredParameterResolverFactories.add(new SimpleResourceParameterResolverFactory(resources));
        registeredParameterResolverFactories.add(ClasspathParameterResolverFactory.forClass(aggregateType));
        registeredHandlerDefinitions.add(ClasspathHandlerDefinition.forClass(aggregateType));
        registeredHandlerEnhancerDefinitions.add(ClasspathHandlerEnhancerDefinition.forClass(aggregateType));
    }

    @SafeVarargs
    @Override
    public final FixtureConfiguration<T> withSubtypes(Class<? extends T>... subtypes) {
        this.subtypes.addAll(Arrays.asList(subtypes));
        return this;
    }

    @Override
    public FixtureConfiguration<T> useStateStorage() {
        this.useStateStorage = true;
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerRepository(Repository<T> repository) {
        this.repository = new IdentifierValidatingRepository<>(repository);
        resources.add(repository);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerRepositoryProvider(RepositoryProvider repositoryProvider) {
        if (repository != null) {
            throw new FixtureExecutionException(
                    "Cannot register a RepositoryProvider since the Repository is already defined in this fixture."
                            + " It is recommended to first a RepositoryProvider"
                            + " and then register or retrieve the Repository."
            );
        }
        this.repositoryProvider = repositoryProvider;
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerAggregateFactory(AggregateFactory<T> aggregateFactory) {
        return registerRepository(LegacyEventSourcingRepository.builder(aggregateFactory.getAggregateType())
                                                               .aggregateFactory(aggregateFactory)
                                                               .eventStore(eventStore)
                                                               .parameterResolverFactory(getParameterResolverFactory())
                                                               .handlerDefinition(getHandlerDefinition())
                                                               .repositoryProvider(getRepositoryProvider())
                                                               .build());
    }

    @Override
    public synchronized FixtureConfiguration<T> registerAnnotatedCommandHandler(final Object annotatedCommandHandler) {
        registerAggregateCommandHandlers();
        explicitCommandHandlersSet = true;
        commandBus.subscribe(new AnnotatedCommandHandlingComponent<>(
                annotatedCommandHandler,
                getParameterResolverFactory(),
                getHandlerDefinition(),
                new ClassBasedMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        ));
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerCommandHandler(Class<?> payloadType,
                                                          LegacyMessageHandler<CommandMessage, CommandResultMessage> commandHandler) {
        return registerCommandHandler(payloadType.getName(), commandHandler);
    }

    @Override
    public FixtureConfiguration<T> registerCommandHandler(String commandName,
                                                          LegacyMessageHandler<CommandMessage, CommandResultMessage> commandHandler) {
        registerAggregateCommandHandlers();
        explicitCommandHandlersSet = true;
        commandBus.subscribe(new QualifiedName(commandName), (CommandHandler) commandHandler);
        return this;
    }


    @Override
    public FixtureConfiguration<T> registerInjectableResource(Object resource) {
        if (explicitCommandHandlersSet) {
            throw new FixtureExecutionException("Cannot inject resources after command handler has been created. " +
                                                        "Configure all resource before calling " +
                                                        "registerCommandHandler() or " +
                                                        "registerAnnotatedCommandHandler()");
        }
        this.resources.add(resource);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerParameterResolverFactory(ParameterResolverFactory parameterResolverFactory) {
        if (repository != null) {
            throw new FixtureExecutionException(
                    "Cannot register more ParameterResolverFactories since the Repository is already defined"
                            + " in this fixture. It is recommended to first register ParameterResolverFactories"
                            + " and then register or retrieve the Repository."
            );
        }
        this.registeredParameterResolverFactories.addFirst(parameterResolverFactory);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerCommandDispatchInterceptor(
            MessageDispatchInterceptor<? super CommandMessage> commandDispatchInterceptor
    ) {
        // TODO #3073 - Revisit Aggregate Test Fixture
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public FixtureConfiguration<T> registerCommandHandlerInterceptor(
            MessageHandlerInterceptor<? super CommandMessage> commandHandlerInterceptor
    ) {
        // TODO #3073 - Revisit Aggregate Test Fixture
        throw new UnsupportedOperationException("Not implemented yet");
    }

//    @Override
//    public FixtureConfiguration<T> registerDeadlineDispatchInterceptor(
//            MessageDispatchInterceptor<? super DeadlineMessage> deadlineDispatchInterceptor) {
//        this.deadlineManager.registerDispatchInterceptor(deadlineDispatchInterceptor);
//        return this;
//    }
//
//    @Override
//    public FixtureConfiguration<T> registerDeadlineHandlerInterceptor(
//            MessageHandlerInterceptor<DeadlineMessage> deadlineHandlerInterceptor) {
//        this.deadlineManager.registerHandlerInterceptor(deadlineHandlerInterceptor);
//        return this;
//    }

    @Override
    public FixtureConfiguration<T> registerFieldFilter(FieldFilter fieldFilter) {
        this.fieldFilters.add(fieldFilter);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerIgnoredField(Class<?> declaringClass, String fieldName) {
        return registerFieldFilter(new IgnoreField(declaringClass, fieldName));
    }

    @Override
    public FixtureConfiguration<T> registerHandlerDefinition(HandlerDefinition handlerDefinition) {
        if (repository != null) {
            throw new FixtureExecutionException(
                    "Cannot register more HandlerDefinitions since the Repository is already defined in this fixture."
                            + " It is recommended to first register HandlerDefinitions"
                            + " and then register or retrieve the Repository."
            );
        }
        this.registeredHandlerDefinitions.addFirst(handlerDefinition);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerHandlerEnhancerDefinition(
            HandlerEnhancerDefinition handlerEnhancerDefinition
    ) {
        if (repository != null) {
            throw new FixtureExecutionException(
                    "Cannot register more HandlerEnhancerDefinitions since the Repository is already defined"
                            + " in this fixture. It is recommended to first register HandlerEnhancerDefinitions"
                            + " and then register or retrieve the Repository."
            );
        }
        this.registeredHandlerEnhancerDefinitions.addFirst(handlerEnhancerDefinition);
        return this;
    }

    @Override
    public FixtureConfiguration<T> registerCommandTargetResolver(CommandTargetResolver commandTargetResolver) {
        this.commandTargetResolver = commandTargetResolver;
        return this;
    }

    @Override
    public TestExecutor<T> given(Object... domainEvents) {
        return given(Arrays.asList(domainEvents));
    }

    @Override
    public TestExecutor<T> andGiven(Object... domainEvents) {
        return andGiven(Arrays.asList(domainEvents));
    }

    @Override
    public TestExecutor<T> givenNoPriorActivity() {
        ensureRepositoryConfiguration();
        clearGivenWhenState();
        return this;
    }

    @Override
    public TestExecutor<T> givenState(Supplier<T> aggregate) {
        if (this.repository == null) {
            this.useStateStorage();
        }

        ensureRepositoryConfiguration();
        LegacyDefaultUnitOfWork.startAndGet(null).execute((ctx) -> {
            try {
                repository.newInstance(aggregate::get);
            } catch (Exception e) {
                throw new FixtureExecutionException(
                        "An exception occurred while trying to initialize repository with given aggregate (using 'givenState')",
                        e);
            }
        });
        clearGivenWhenState();
        return this;
    }

    @Override
    public TestExecutor<T> given(List<?> domainEvents) {
        ensureRepositoryConfiguration();
        clearGivenWhenState();
        return andGiven(domainEvents);
    }

    @Override
    public TestExecutor<T> andGiven(List<?> domainEvents) {
        if (this.useStateStorage) {
            throw new FixtureExecutionException(
                    "Given events not supported, because the fixture is configured to use state storage");
        }

        for (Object event : domainEvents) {
            Object payload = event;
            Metadata metadata = null;
            String type = aggregateType.getSimpleName();
            if (event instanceof Message) {
                payload = ((Message) event).payload();
                metadata = ((Message) event).metadata();
            }
            if (event instanceof DomainEventMessage) {
                type = ((DomainEventMessage) event).getType();
            }
            GenericDomainEventMessage eventMessage = new GenericDomainEventMessage(
                    type,
                    aggregateIdentifier,
                    sequenceNumber++,
                    new GenericMessage(new MessageType(payload.getClass()), payload, metadata),
                    Instant.now()
//                    deadlineManager.getCurrentDateTime()
            );
            this.givenEvents.add(eventMessage);
        }
        return this;
    }

    @Override
    public TestExecutor<T> givenCommands(Object... commands) {
        return givenCommands(Arrays.asList(commands));
    }

    @Override
    public TestExecutor<T> andGivenCommands(Object... commands) {
        return andGivenCommands(Arrays.asList(commands));
    }

    @Override
    public TestExecutor<T> givenCommands(List<?> commands) {
        clearGivenWhenState();
        return andGivenCommands(commands);
    }

    // TODO 3073 - Current doc states "Similar to the given events, if the provided command is of type `CommandMessage`, the fixture dispatches it as is."
    //  It's not true anymore, because GenericCommandMessage constructor had logic of passing existing message (as asCommandMessage static factory had before), it's not present in current implementation.
    //  We decided to drop support for passing Message as payload, but it require API adjustments in order to pass Metadata somehow.
    @Override
    public TestExecutor<T> andGivenCommands(List<?> commands) {
        finalizeConfiguration();
        for (Object command : commands) {
            CompletableFuture<Message> result = new CompletableFuture<>();
            CommandMessage commandMessage =
                    new GenericCommandMessage(new MessageType(command.getClass()), command);
            executeAtSimulatedTime(() -> commandBus.dispatch(commandMessage,
                                                             new LegacyMessageSupportingContext(commandMessage))
                                                   .whenComplete(FutureUtils.alsoComplete(result)));
            result.join();
            givenEvents.addAll(storedEvents);
            storedEvents.clear();
        }
        publishedEvents.clear();
        return this;
    }

    private void executeAtSimulatedTime(Runnable runnable) {
        Clock previousClock = ClockUtils.get();
        try {
            ClockUtils.set(Clock.fixed(currentTime(), ZoneOffset.UTC));
            runnable.run();
        } finally {
            ClockUtils.set(previousClock);
        }
    }

    @Override
    public TestExecutor<T> givenCurrentTime(Instant currentTime) {
        clearGivenWhenState();
        return andGivenCurrentTime(currentTime);
    }

    @Override
    public TestExecutor<T> andGivenCurrentTime(Instant currentTime) {
//        deadlineManager.initializeAt(currentTime);
        return this;
    }

    @Override
    public Instant currentTime() {
        return Instant.now();//deadlineManager.getCurrentDateTime();
    }

    @Override
    public ResultValidator<T> whenTimeElapses(Duration elapsedTime) {
        logger.debug("Starting WHEN-phase");
//        deadlineManager.advanceTimeBy(elapsedTime, this::handleDeadline);
        return buildResultValidator();
    }

    @Override
    public ResultValidator<T> whenTimeAdvancesTo(Instant newPointInTime) {
        logger.debug("Starting WHEN-phase");
//        deadlineManager.advanceTimeTo(newPointInTime, this::handleDeadline);
        return buildResultValidator();
    }

    @Override
    public ResultValidator<T> when(Object command) {
        return when(command, Metadata.emptyInstance());
    }

    @Override
    public ResultValidator<T> when(Object command, Map<String, String> metadata) {
        return when(resultValidator -> {
            CommandMessage commandMessage =
                    new GenericCommandMessage(new MessageType(command.getClass()), command, metadata);
            commandBus.dispatch(commandMessage, new LegacyMessageSupportingContext(commandMessage))
                      .whenComplete((r, e) -> {
                          if (e == null) {
                              resultValidator.recordResult(commandMessage, r);
                          } else {
                              resultValidator.recordException(e.getCause());
                          }
                      });
        });
    }

    @Override
    public ResultValidator<T> whenConstructing(Callable<T> aggregateFactory) {
        return when(validator -> LegacyDefaultUnitOfWork.startAndGet(null).execute((ctx) -> {
            try {
                repository.newInstance(aggregateFactory);
            } catch (Exception | AssertionError e) {
                // Catching AssertionErrors as the Repository of the Fixture may throw them.
                validator.recordException(e);
            }
        }));
    }

    @Override
    public ResultValidator<T> whenInvoking(String aggregateId, Consumer<T> aggregateSupplier) {
        return when(validator -> LegacyDefaultUnitOfWork.startAndGet(null).execute((ctx) -> {
            try {
                repository.load(aggregateId)
                          .execute(aggregateSupplier);
            } catch (Exception | AssertionError e) {
                // Catching AssertionErrors as the Repository of the Fixture may throw them.
                validator.recordException(e);
            }
        }));
    }

    private ResultValidator<T> when(Consumer<ResultValidatorImpl<T>> whenPhase) {
        logger.debug("Starting WHEN-phase");
        finalizeConfiguration();
        final MatchAllFieldFilter fieldFilter = new MatchAllFieldFilter(fieldFilters);
        ResultValidatorImpl<T> resultValidator = new ResultValidatorImpl<>(publishedEvents,
                                                                           fieldFilter,
                                                                           () -> repository.getAggregate()
//                , deadlineManager
        );

        executeAtSimulatedTime(() -> whenPhase.accept(resultValidator));

        if (!repository.rolledBack) {
            Aggregate<T> workingAggregate = repository.aggregate;
            detectIllegalStateChanges(fieldFilter, workingAggregate);
        }
        resultValidator.assertValidRecording();
        logger.debug("Starting EXPECT-phase");
        return resultValidator;
    }

    /**
     * Handles the given {@code deadlineMessage} in the aggregate described by the given {@code aggregateDescriptor}.
     * Deadline message is handled in the scope of a {@link LegacyUnitOfWork}. If handling the deadline results in an
     * exception, the exception will be wrapped in a {@link FixtureExecutionException}.
     * <p>
     * //     * @param aggregateDescriptor A {@link ScopeDescriptor} describing the aggregate under test //     * @param
     * deadlineMessage     The {@code DeadlineMessage} to be handled
     */
//    protected void handleDeadline(ScopeDescriptor aggregateDescriptor, DeadlineMessage deadlineMessage)
//            throws Exception {
//        ensureRepositoryConfiguration();
//        repository.send(deadlineMessage, new LegacyMessageSupportingContext(deadlineMessage), aggregateDescriptor);
//    }
    private ResultValidator<T> buildResultValidator() {
        MatchAllFieldFilter fieldFilter = new MatchAllFieldFilter(fieldFilters);
        ResultValidatorImpl<T> resultValidator = new ResultValidatorImpl<>(publishedEvents,
                                                                           fieldFilter,
                                                                           () -> repository.getAggregate()
//                , deadlineManager
        );
        resultValidator.assertValidRecording();
        logger.debug("Starting EXPECT-phase");
        return resultValidator;
    }

    private void finalizeConfiguration() {
        registerAggregateCommandHandlers();
        explicitCommandHandlersSet = true;
    }

    private void registerAggregateCommandHandlers() {
        ensureRepositoryConfiguration();
        if (!explicitCommandHandlersSet) {
            AggregateAnnotationCommandHandler.Builder<T> builder = AggregateAnnotationCommandHandler.<T>builder()
                                                                                                    .aggregateType(
                                                                                                            aggregateType)
                                                                                                    .aggregateModel(
                                                                                                            aggregateModel())
                                                                                                    .parameterResolverFactory(
                                                                                                            getParameterResolverFactory())
                                                                                                    .repository(this.repository);

            if (commandTargetResolver != null) {
                builder.commandTargetResolver(commandTargetResolver);
            }

            commandBus.subscribe(builder.build());
        }
    }

    private void ensureRepositoryConfiguration() {
        if (repository != null) {
            return;
        }

        if (this.useStateStorage) {
            this.registerRepository(new InMemoryRepository<>(
                    aggregateType,
                    subtypes,
                    eventStore,
                    getParameterResolverFactory(),
                    getHandlerDefinition(),
                    getRepositoryProvider()));
        } else {
            AggregateModel<T> aggregateModel = aggregateModel();
            this.registerRepository(LegacyEventSourcingRepository.builder(aggregateType)
                                                                 .aggregateModel(aggregateModel)
                                                                 .aggregateFactory(
                                                                         new GenericAggregateFactory<>(aggregateModel)
                                                                 )
                                                                 .eventStore(eventStore)
                                                                 .parameterResolverFactory(getParameterResolverFactory())
                                                                 .handlerDefinition(getHandlerDefinition())
                                                                 .repositoryProvider(getRepositoryProvider())
                                                                 .build());
        }
    }

    private AggregateModel<T> aggregateModel() {
        return AnnotatedAggregateMetaModelFactory.inspectAggregate(aggregateType,
                                                                   getParameterResolverFactory(),
                                                                   getHandlerDefinition(),
                                                                   subtypes);
    }

    private ParameterResolverFactory getParameterResolverFactory() {
        return MultiParameterResolverFactory.ordered(registeredParameterResolverFactories);
    }

    private HandlerDefinition getHandlerDefinition() {
        HandlerEnhancerDefinition handlerEnhancerDefinition =
                MultiHandlerEnhancerDefinition.ordered(registeredHandlerEnhancerDefinitions);
        return MultiHandlerDefinition.ordered(registeredHandlerDefinitions, handlerEnhancerDefinition);
    }

    private RepositoryProvider getRepositoryProvider() {
        if (repositoryProvider == null) {
            registerRepositoryProvider(new DefaultRepositoryProvider());
        }
        return repositoryProvider;
    }

    private void detectIllegalStateChanges(MatchAllFieldFilter fieldFilter, Aggregate<T> workingAggregate) {
        logger.debug("Starting separate Unit of Work for the purpose of checking illegal state changes in Aggregate");
        if (aggregateIdentifier != null && workingAggregate != null && reportIllegalStateChange) {
            LegacyUnitOfWork<?> uow = LegacyDefaultUnitOfWork.startAndGet(null);
            try {
                Aggregate<T> aggregate2 = repository.delegate.load(aggregateIdentifier);
                if (workingAggregate.isDeleted()) {
                    throw new AxonAssertionError("The working aggregate was considered deleted, " +
                                                         "but the Repository still contains a non-deleted copy of " +
                                                         "the aggregate. Make sure the aggregate explicitly marks " +
                                                         "itself as deleted in an EventHandler.");
                }
                assertValidWorkingAggregateState(aggregate2, fieldFilter, workingAggregate);
            } catch (AggregateNotFoundException notFound) {
                // The identifier == null if an aggregate creating command handler decided not to create the aggregate.
                if (!workingAggregate.isDeleted() && workingAggregate.identifier() != null) {
                    throw new AxonAssertionError("The working aggregate was not considered deleted, " //NOSONAR
                                                         + "but the Repository cannot recover the state of the " +
                                                         "aggregate, as it is considered deleted there.");
                }
            } catch (Exception e) {
                throw new FixtureExecutionException("An Exception occurred while reconstructing the Aggregate from " +
                                                            "given and published events. This may be an indication " +
                                                            "that the aggregate cannot be recreated from its events.",
                                                    e);
            } finally {
                // rollback to prevent changes bing pushed to event store
                uow.rollback();
            }
        }
    }

    private void assertValidWorkingAggregateState(Aggregate<T> eventSourcedAggregate, MatchAllFieldFilter fieldFilter,
                                                  Aggregate<T> workingAggregate) {
        HashSet<ComparationEntry> comparedEntries = new HashSet<>();
        if (!workingAggregate.rootType().equals(eventSourcedAggregate.rootType())) {
            throw new AxonAssertionError(String.format("The aggregate loaded based on the generated events seems to " +
                                                               "be of another type than the original.\n" +
                                                               "Working type: <%s>\nEvent Sourced type: <%s>",
                                                       workingAggregate.rootType().getName(),
                                                       eventSourcedAggregate.rootType().getName()));
        }
        ensureValuesEqual(workingAggregate.invoke(Function.identity()),
                          eventSourcedAggregate.invoke(Function.identity()),
                          eventSourcedAggregate.rootType().getName(),
                          comparedEntries,
                          fieldFilter);
    }

    private void ensureValuesEqual(Object workingValue,
                                   Object eventSourcedValue,
                                   String propertyPath,
                                   Set<ComparationEntry> comparedEntries,
                                   FieldFilter fieldFilter) {
        if (Objects.equals(workingValue, eventSourcedValue)) {
            // they're equal, nothing more to check...
            return;
        }

        if ((workingValue == null || hasEqualsMethod(workingValue.getClass()))
                || (eventSourcedValue == null || hasEqualsMethod(eventSourcedValue.getClass()))) {
            failIllegalStateChange(workingValue, eventSourcedValue, propertyPath);
        } else if (comparedEntries.add(new ComparationEntry(workingValue, eventSourcedValue))
                && !hasEqualsMethod(workingValue.getClass())) {
            try {
                for (Field field : fieldsOf(workingValue.getClass())) {
                    if (fieldFilter.accept(field)
                            && !Modifier.isStatic(field.getModifiers())
                            && !Modifier.isTransient(field.getModifiers())) {
                        ensureAccessible(field);
                        String newPropertyPath = propertyPath + "." + field.getName();

                        Object workingFieldValue = getFieldValue(field, workingValue);
                        Object eventSourcedFieldValue = getFieldValue(field, eventSourcedValue);
                        ensureValuesEqual(workingFieldValue,
                                          eventSourcedFieldValue,
                                          newPropertyPath,
                                          comparedEntries,
                                          fieldFilter);
                    }
                }
            } catch (Exception e) {
                logger.debug("Exception while attempting to verify deep equality.", e);
                failIllegalStateChange(workingValue, eventSourcedValue, propertyPath);
            }
        }
    }

    private void failIllegalStateChange(Object workingValue, Object eventSourcedValue, String propertyPath) {
        throw new AxonAssertionError(format("Illegal state change detected! " +
                                                    "Property \"%s\" has different value when sourcing events.\n" +
                                                    "Working aggregate value:     <%s>\n" +
                                                    "Value after applying events: <%s>", propertyPath, workingValue,
                                            eventSourcedValue));
    }

    private void clearGivenWhenState() {
        logger.debug("Starting GIVEN-phase");
        storedEvents = new LinkedList<>();
        publishedEvents = new ArrayList<>();
        givenEvents = new LinkedList<>();
        sequenceNumber = 0;
    }

    @Override
    public void setReportIllegalStateChange(boolean reportIllegalStateChange) {
        this.reportIllegalStateChange = reportIllegalStateChange;
    }

    @Override
    public CommandBus getCommandBus() {
        return commandBus;
    }

    @Override
    public EventBus getEventBus() {
        return null;
    }

    @Override
    public EventStore getEventStore() {
        return eventStore;
    }

    @Override
    public Repository<T> getRepository() {
        ensureRepositoryConfiguration();
        return repository;
    }

    private record ComparationEntry(Object workingObject, Object eventSourceObject) {

    }

    private static class IdentifierValidatingRepository<T> implements Repository<T> {

        private final Repository<T> delegate;
        private Aggregate<T> aggregate;
        private boolean rolledBack;

        public IdentifierValidatingRepository(Repository<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Aggregate<T> loadOrCreate(String aggregateIdentifier,
                                         Callable<T> factoryMethod) throws Exception {
            CurrentUnitOfWork.get().onRollback(u -> this.rolledBack = true);
            aggregate = delegate.loadOrCreate(aggregateIdentifier, factoryMethod);
            return aggregate;
        }

        @Override
        public Aggregate<T> newInstance(Callable<T> factoryMethod) throws Exception {
            CurrentUnitOfWork.get().onRollback(u -> this.rolledBack = true);
            aggregate = delegate.newInstance(factoryMethod);
            return aggregate;
        }

        @Override
        public Aggregate<T> load(String aggregateIdentifier) {
            CurrentUnitOfWork.get().onRollback(u -> this.rolledBack = true);
            aggregate = delegate.load(aggregateIdentifier);
            validateIdentifier(aggregateIdentifier, aggregate);
            return aggregate;
        }

        private void validateIdentifier(String aggregateIdentifier, Aggregate<T> aggregate) {
            if (aggregateIdentifier != null && !aggregateIdentifier.equals(aggregate.identifierAsString())) {
                throw new AssertionError(String.format(
                        "The aggregate used in this fixture was initialized with an identifier different than " +
                                "the one used to load it. Loaded [%s], but actual identifier is [%s].\n" +
                                "Make sure the identifier passed during construction matches that of the when-phase.",
                        aggregateIdentifier, aggregate.identifierAsString()));
            }
        }

        public Aggregate<T> getAggregate() {
            Assert.state(!rolledBack, () -> "The state of this aggregate cannot be retrieved because it " +
                    "has been modified in a Unit of Work that was rolled back");

            return aggregate;
        }

        @Override
        public void send(Message message,
                         ProcessingContext context,
                         ScopeDescriptor scopeDescription) throws Exception {
            if (canResolve(scopeDescription)) {
                load(((AggregateScopeDescriptor) scopeDescription).getIdentifier().toString()).handle(message, context);
            }
        }

        @Override
        public boolean canResolve(ScopeDescriptor scopeDescription) {
            return scopeDescription instanceof AggregateScopeDescriptor;
        }
    }

    private static class InMemoryRepository<T> implements Repository<T> {

        private final EventStore eventBus;
        private final RepositoryProvider repositoryProvider;
        private final AggregateModel<T> aggregateModel;
        private AnnotatedAggregate<T> storedAggregate;

        protected InMemoryRepository(Class<T> aggregateType,
                                     Set<Class<? extends T>> subtypes,
                                     EventStore eventBus,
                                     ParameterResolverFactory parameterResolverFactory,
                                     HandlerDefinition handlerDefinition,
                                     RepositoryProvider repositoryProvider) {
            this.aggregateModel = AnnotatedAggregateMetaModelFactory.inspectAggregate(
                    aggregateType, parameterResolverFactory, handlerDefinition, subtypes
            );
            this.eventBus = eventBus;
            this.repositoryProvider = repositoryProvider;
        }

        @Override
        public Aggregate<T> newInstance(Callable<T> factoryMethod) throws Exception {
            Assert.state(storedAggregate == null,
                         () -> "Creating an Aggregate while one is already stored. Test fixtures do not allow multiple instances to be stored.");
            storedAggregate = AnnotatedAggregate.initialize(factoryMethod,
                                                            aggregateModel,
                                                            null, // TODO replace for workable EventBus
                                                            repositoryProvider,
                                                            true);
            return storedAggregate;
        }

        @Override
        public Aggregate<T> load(String aggregateIdentifier) {
            if (storedAggregate == null) {
                throw new AggregateNotFoundException(aggregateIdentifier,
                                                     "Aggregate not found. No aggregate has been stored yet.");
            }
            if (!aggregateIdentifier.equals(storedAggregate.identifier().toString())) {
                throw new AggregateNotFoundException(
                        aggregateIdentifier,
                        "Aggregate not found. Did you mean to load " + storedAggregate.identifier() + "?"
                );
            }
            if (storedAggregate.isDeleted()) {
                throw new AggregateNotFoundException(aggregateIdentifier, "Aggregate not found. It has been deleted.");
            }
            return storedAggregate;
        }

        @Override
        public void send(Message message,
                         ProcessingContext context,
                         ScopeDescriptor scopeDescription) throws Exception {
            if (canResolve(scopeDescription)) {
                load(((AggregateScopeDescriptor) scopeDescription).getIdentifier().toString()).handle(message, context);
            }
        }

        @Override
        public boolean canResolve(ScopeDescriptor scopeDescription) {
            return scopeDescription instanceof AggregateScopeDescriptor;
        }

        @Override
        public Aggregate<T> loadOrCreate(String aggregateIdentifier,
                                         Callable<T> factoryMethod) throws Exception {
            if (storedAggregate == null) {
                return newInstance(factoryMethod);
            }

            return load(aggregateIdentifier);
        }
    }

    private class RecordingEventStore implements EventStore {

//        @Override
//        public DomainEventStream readEvents(String identifier) {
//            if (aggregateIdentifier != null && !aggregateIdentifier.equals(identifier)) {
//                String exceptionMessage = format(
//                        "The aggregate identifier used in the 'when' step does not resemble the aggregate identifier"
//                                + " used in the 'given' step. "
//                                + "Please make sure the when-identifier [%s] resembles the given-identifier [%s].",
//                        identifier, aggregateIdentifier
//                );
//                throw new EventStoreException(exceptionMessage);
//            } else if (aggregateIdentifier == null) {
//                aggregateIdentifier = identifier;
//                injectAggregateIdentifier();
//            }
//            List<DomainEventMessage> allEvents = new ArrayList<>(givenEvents);
//            allEvents.addAll(storedEvents);
//            if (allEvents.isEmpty()) {
//                throw new AggregateNotFoundException(identifier,
//                                                     "No 'given' events were configured for this aggregate, " +
//                                                             "nor have any events been stored.");
//            }
//            return DomainEventStream.of(allEvents);
//        }
//
//        @Override
//        public void publish(List<? extends EventMessage> events) {
//            if (CurrentUnitOfWork.isStarted()) {
//                CurrentUnitOfWork.get().onPrepareCommit(u -> doAppendEvents(events));
//            } else {
//                doAppendEvents(events);
//            }
//        }
//
//        protected void doAppendEvents(List<? extends EventMessage> events) {
//            events.forEach(e -> {
//                if (!DomainEventMessage.class.isInstance(e)) {
//                    // Since the event is not a domain event, only publish it i.o. validating/storing it.
//                    publishedEvents.add(e);
//                    return;
//                }
//                DomainEventMessage event = (DomainEventMessage) e;
//
//                if (aggregateIdentifier == null) {
//                    aggregateIdentifier = event.getAggregateIdentifier();
//                    injectAggregateIdentifier();
//                }
//
//                DomainEventMessage lastEvent = (storedEvents.isEmpty() ? givenEvents : storedEvents).peekLast();
//
//                if (lastEvent != null) {
//                    if (!lastEvent.getAggregateIdentifier().equals(event.getAggregateIdentifier())) {
//                        throw new EventStoreException("Writing events for an unexpected aggregate. This could "
//                                                              + "indicate that a wrong aggregate is being triggered.");
//                    } else if (lastEvent.getSequenceNumber() != event.getSequenceNumber() - 1) {
//                        throw new EventStoreException(format(
//                                "Unexpected sequence number on stored event. " + "Expected %s, \n but got %s.",
//                                lastEvent.getSequenceNumber() + 1, event.getSequenceNumber()
//                        ));
//                    }
//                }
//                publishedEvents.add(event);
//                storedEvents.add(event);
//            });
//        }
//
//        private void injectAggregateIdentifier() {
//            List<DomainEventMessage> oldEvents = new ArrayList<>(givenEvents);
//            givenEvents.clear();
//            for (DomainEventMessage oldEvent : oldEvents) {
//                if (oldEvent.getAggregateIdentifier() == null) {
//                    givenEvents.add(new GenericDomainEventMessage(oldEvent.getType(),
//                                                                    aggregateIdentifier,
//                                                                    oldEvent.getSequenceNumber(),
//                                                                    oldEvent.identifier(),
//                                                                    oldEvent.type(),
//                                                                    oldEvent.payload(),
//                                                                    oldEvent.metadata(),
//                                                                    oldEvent.timestamp()));
//                } else {
//                    givenEvents.add(oldEvent);
//                }
//            }
//        }
//
//        @Override
//        public TrackingEventStream openStream(TrackingToken trackingToken) {
//            throw new UnsupportedOperationException();
//        }
//
//        @Override
//        public void storeSnapshot(DomainEventMessage snapshot) {
//            // A dedicated implementation is not necessary for test fixture.
//        }
//
//        @NonNull
//        @Override
//        public Registration subscribe(Consumer<List<? extends EventMessage>> eventProcessor) {
//            return () -> true;
//        }
//
//        public @NonNull
//        Registration registerDispatchInterceptor(
//                MessageDispatchInterceptor<? super EventMessage> dispatchInterceptor) {
//            return () -> true;
//        }

        @Override
        public EventStoreTransaction transaction(ProcessingContext processingContext) {
            return null;
        }

        @Override
        public CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                               List<? extends EventMessage> events) {
            return null;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {

        }

        @Override
        public MessageStream<EventMessage> open(StreamingCondition condition,
                                                @Nullable ProcessingContext context) {
            return null;
        }

        @Override
        public CompletableFuture<TrackingToken> firstToken(@Nullable ProcessingContext context) {
            return null;
        }

        @Override
        public CompletableFuture<TrackingToken> latestToken(@Nullable ProcessingContext context) {
            return null;
        }

        @Override
        public CompletableFuture<TrackingToken> tokenAt(Instant at, @Nullable ProcessingContext context) {
            return null;
        }

        @Override
        public Registration subscribe(
                BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> eventsBatchConsumer) {
            return null;
        }
    }

    private class DefaultRepositoryProvider implements RepositoryProvider {

        @Override
        public <R> Repository<R> repositoryFor(Class<R> aggregateType) {
            return new CreationalRepository<>(aggregateType, this);
        }
    }

    private class CreationalRepository<R> implements Repository<R> {

        private final Class<R> aggregateType;
        private final RepositoryProvider repositoryProvider;

        private CreationalRepository(Class<R> aggregateType,
                                     RepositoryProvider repositoryProvider) {
            this.aggregateType = aggregateType;
            this.repositoryProvider = repositoryProvider;
        }

        @Override
        public Aggregate<R> load(String aggregateIdentifier) {
            throw new UnsupportedOperationException(
                    "Default repository does not mock loading of an aggregate, only creation of it");
        }

        @Override
        public Aggregate<R> newInstance(Callable<R> factoryMethod) throws Exception {
            AggregateModel<R> aggregateModel = AnnotatedAggregateMetaModelFactory.inspectAggregate(
                    aggregateType, getParameterResolverFactory(), getHandlerDefinition()
            );
//            return EventSourcedAggregate.initialize(factoryMethod, aggregateModel, eventStore, repositoryProvider);
            return null;
        }

        @Override
        public void send(Message message, ProcessingContext context, ScopeDescriptor scopeDescription) {
            throw new UnsupportedOperationException(
                    "Default repository does not mock loading of an aggregate, only creation of it");
        }

        @Override
        public boolean canResolve(ScopeDescriptor scopeDescription) {
            return false;
        }
    }
}
