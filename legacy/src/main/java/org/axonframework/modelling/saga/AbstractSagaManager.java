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

package org.axonframework.modelling.saga;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.FutureUtils;
import org.axonframework.common.IdentifierFactory;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.ScopeAware;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.replay.ResetContext;
import org.axonframework.messaging.eventhandling.replay.ResetNotSupportedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * Abstract implementation of the SagaManager interface that provides basic functionality required by most SagaManager
 * implementations. Provides support for Saga lifecycle management and asynchronous handling of events.
 *
 * @author Allard Buijze
 * @since 0.7
 */
public abstract class AbstractSagaManager<T> implements EventHandlingComponent, ScopeAware {

    private static final Logger logger = LoggerFactory.getLogger(AbstractSagaManager.class);

    private final SagaRepository<T> sagaRepository;
    private final Class<T> sagaType;
    private final Supplier<T> sagaFactory;

    /**
     * Instantiate a {@link AbstractSagaManager} based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the {@code sagaType}, {@code sagaFactory} and {@link SagaRepository} are not {@code null}, and
     * will throw an {@link AxonConfigurationException} if any of them is {@code null}.
     *
     * @param builder the {@link Builder} used to instantiate a {@link AbstractSagaManager} instance
     */
    protected AbstractSagaManager(Builder<T> builder) {
        builder.validate();
        this.sagaRepository = builder.sagaRepository;
        this.sagaType = builder.sagaType;
        this.sagaFactory = builder.sagaFactory;
    }

    /**
     * {@inheritDoc}
     * <p>
     * As an {@link EventHandlingComponent}, this method does not receive the {@link Segment} to filter Saga instances
     * with directly. Instead, the {@code EventProcessor} attaches the {@code Segment} it is currently processing to
     * the given {@code context} as a resource, retrievable through
     * {@link Segment#fromContext(org.axonframework.messaging.core.Context)}. When absent, for example when this
     * method is invoked outside a segmented {@code EventProcessor}, the {@link Segment#ROOT_SEGMENT} is assumed,
     * matching every Saga instance.
     * <p>
     * Two distinct questions decide what happens here. {@link #canHandle(EventMessage, ProcessingContext)} asks whether
     * the Saga <b>type</b> declares a handler for this event at all, and {@link Saga#canHandle(EventMessage,
     * ProcessingContext)} asks whether an individual instance holds the {@link AssociationValue} its handler resolves
     * from the event. Only the second decides whether a Saga counts as having taken the event, which is what a
     * {@link SagaCreationPolicy#IF_NONE_FOUND} policy consults before starting a new instance.
     */
    @Override
    public MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context) {
        if (!canHandle(event, context)) {
            return MessageStream.empty();
        }

        Segment segment = Segment.fromContext(context).orElse(Segment.ROOT_SEGMENT);
        Set<AssociationValue> associationValues = extractAssociationValues(event, context);
        List<String> sagaIds =
                associationValues.stream()
                                 .flatMap(associationValue -> sagaRepository.find(associationValue, context).stream())
                                 .collect(Collectors.toList());
        Set<Saga<T>> sagas =
                sagaIds.stream()
                       .filter(sagaId -> matchesSegment(segment, sagaId))
                       .map(sagaId -> sagaRepository.load(sagaId, context))
                       .filter(Objects::nonNull)
                       .filter(Saga::isActive)
                       .collect(Collectors.toCollection(HashSet::new));
        boolean sagaMatchesOtherSegment = sagaIds.stream().anyMatch(sagaId -> !matchesSegment(segment, sagaId));

        MessageStream<Message> result = MessageStream.empty();
        boolean sagaOfTypeInvoked = false;
        for (Saga<T> saga : sagas) {
            if (saga.canHandle(event, context)) {
                // Deferred on purpose: a lazily concatenated stream is only consumed once the preceding one completed
                // successfully, so once one Saga fails, the remaining Sagas are not invoked and their side effects
                // cannot escape a unit of work that is going to roll back.
                result = result.concatWith(() -> saga.handle(event, context));
                // Deliberately set before the Saga is invoked: a Saga whose handler fails still took the event,
                // which is what Axon Framework 4's error handling meant by returning true from an invocation that threw.
                sagaOfTypeInvoked = true;
            }
        }

        SagaInitializationPolicy initializationPolicy = getSagaCreationPolicy(event, context);
        if (shouldCreateSaga(segment, sagaOfTypeInvoked || sagaMatchesOtherSegment, initializationPolicy)) {
            // Deferred for the same reason: a Saga that failed above stops the new Saga from being created and
            // invoked at all.
            result = result.concatWith(
                    () -> startNewSaga(event, context, initializationPolicy.getInitialAssociationValue(), segment)
            );
        }
        return result.ignoreEntries().cast();
    }

    private boolean shouldCreateSaga(Segment segment, boolean sagaInvoked,
                                     SagaInitializationPolicy initializationPolicy) {
        return ((initializationPolicy.getCreationPolicy() == SagaCreationPolicy.ALWAYS
                || (!sagaInvoked && initializationPolicy.getCreationPolicy() == SagaCreationPolicy.IF_NONE_FOUND)))
                && segment.matches(initializationPolicy.getInitialAssociationValue());
    }

    private MessageStream<Message> startNewSaga(EventMessage event,
                                                ProcessingContext context,
                                                AssociationValue associationValue,
                                                Segment segment) {
        Saga<T> newSaga = sagaRepository.createInstance(createSagaIdentifier(segment), sagaFactory, context);
        newSaga.getAssociationValues().add(associationValue);
        return newSaga.canHandle(event, context)
                ? newSaga.handle(event, context)
                : MessageStream.empty();
    }

    /**
     * Creates a Saga identifier that will cause a Saga instance to be considered part of the given {@code segment}.
     *
     * @param segment The segment the identifier must match with
     * @return an identifier for a newly created Saga
     * @implSpec This implementation will repeatedly generate identifier using the {@link IdentifierFactory}, until one
     * is returned that matches the given segment. See {@link #matchesSegment(Segment, String)}.
     */
    protected String createSagaIdentifier(Segment segment) {
        String identifier;

        do {
            identifier = IdentifierFactory.getInstance().generateIdentifier();
        } while (!matchesSegment(segment, identifier));
        return identifier;
    }

    /**
     * Checks whether the given {@code sagaId} matches with the given {@code segment}.
     * <p>
     * For any complete set of segments, exactly one segment matches with any value.
     * <p>
     *
     * @param segment The segment to validate the identifier for
     * @param sagaId  The identifier to test
     * @return {@code true} if the identifier matches the segment, otherwise {@code false}
     * @implSpec This implementation uses the {@link Segment#matches(Object)} to match against the Saga identifier
     */
    protected boolean matchesSegment(Segment segment, String sagaId) {
        return segment.matches(sagaId);
    }

    /**
     * Returns the Saga Initialization Policy for a Saga of the given {@code sagaType} and {@code event}. This policy
     * provides the conditions to create new Saga instance, as well as the initial association of that saga.
     *
     * @param event   The Event that is being dispatched to Saga instances.
     * @param context The {@link ProcessingContext} in which the event is being processed.
     * @return The initialization policy for the Saga.
     */
    protected abstract SagaInitializationPolicy getSagaCreationPolicy(EventMessage event, ProcessingContext context);

    /**
     * Extracts the AssociationValues from the given {@code event} as relevant for a Saga of given {@code sagaType}. A
     * single event may be associated with multiple values.
     *
     * @param event   The event containing the association information.
     * @param context The {@link ProcessingContext} in which the event is being processed.
     * @return The AssociationValues indicating which Sagas should handle given event.
     */
    protected abstract Set<AssociationValue> extractAssociationValues(EventMessage event, ProcessingContext context);

    /**
     * Indicates whether a Saga of the given {@code sagaType} has a handler for the given {@code event}.
     * <p>
     * This check is independent of the {@link EventMessage#type()}: Sagas resolve their handlers by inspecting the
     * {@link EventMessage#payload() payload's} runtime type, the same way a {@code @SagaEventHandler} method is
     * matched, since the message name carried by a {@code MessageType} is not guaranteed to correlate with the
     * payload's class. That makes it stricter than {@link #supports(QualifiedName)}, which the
     * {@code EventProcessor} consults before it delivers an event, so this method is asked again on the way in.
     * <p>
     * This is a question about the Saga type, not about any one instance. For the instance-level question, see
     * {@link Saga#canHandle(EventMessage, ProcessingContext)}.
     *
     * @param event   The event to check for a handler.
     * @param context The {@link ProcessingContext} in which the event is being processed.
     * @return {@code true} if a Saga of the given {@code sagaType} has a handler for the given {@code event},
     * {@code false} otherwise.
     */
    protected abstract boolean canHandle(EventMessage event, ProcessingContext context);

    @Override
    public abstract Set<QualifiedName> supportedEvents();

    /**
     * Returns the class of Saga managed by this SagaManager
     *
     * @return the managed saga type
     */
    public Class<T> getSagaType() {
        return sagaType;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Always the {@link SequencingPolicy#BROADCAST} sentinel, so every {@link Segment} of an {@code EventProcessor} is
     * offered every event this Saga type handles. A Saga manager is never segment-routed by the event: which
     * {@code Segment} owns a Saga follows from the Saga's identifier, which is only known after the association lookup
     * in {@link #handle(EventMessage, ProcessingContext)}, and that lookup needs a {@link SagaRepository} and a unit of
     * work that do not exist while an event is merely being scheduled. Axon Framework 4 reached the same arrangement
     * from the other side, by answering its own admission check without consulting the {@code Segment} at all.
     * <p>
     * Deriving the identifier from the event instead would route each event to a single {@code Segment}, and that
     * {@code Segment} need not be the one owning the Saga. A Saga that associates with a second value is enough to
     * break it: the follow-up event carrying that value hashes elsewhere, the owning {@code Segment} is never offered
     * it, and the {@code Segment} that is offered it filters the Saga out again by identifier. The event is dropped
     * with nothing logged.
     * <p>
     * Broadcasting does not mean a Saga handles an event more than once. A {@code TokenStore} claim makes a
     * {@code Segment} live on exactly one node, {@link #matchesSegment(Segment, String)} lets only the {@code Segment}
     * owning the Saga identifier invoke it, and creation is claimed by the single {@code Segment} matching the initial
     * {@link AssociationValue}. Two limits are inherited from Axon Framework 4 rather than introduced here: a
     * subscribing {@code EventProcessor} has no segments and no token store, so every node falls back to
     * {@link Segment#ROOT_SEGMENT} and starts its own Saga; and a {@link SagaStore} is keyed on the Saga identifier
     * alone, with no uniqueness constraint over association values, so segment ownership is the only guard rather than
     * a last line of defence at the storage layer.
     */
    @Override
    public Object sequenceIdentifierFor(EventMessage event, ProcessingContext context) {
        return SequencingPolicy.BROADCAST;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Always {@code false}. Replaying events into Sagas would rerun their side effects, which is why Axon Framework 4
     * refused a reset as well.
     */
    @Override
    public boolean supportsReset() {
        return false;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Throws a {@link ResetNotSupportedException} rather than returning a failed {@link MessageStream}, which the
     * declared return type would otherwise invite. That is deliberate: an {@code EventProcessor} gates both its reset
     * handling and its {@code resetTokens} call on {@link #supportsReset()}, which is {@code false} here, so this
     * method is unreachable through one and only a direct caller can arrive at it. A caller reaching past the gate has
     * made a programming error, and Axon Framework 4 threw for it too.
     *
     * @throws ResetNotSupportedException always, since Sagas cannot replay their side effects
     */
    @Override
    public MessageStream.Empty<Message> handle(ResetContext resetContext, ProcessingContext context) {
        throw new ResetNotSupportedException("Sagas do no support resetting tokens");
    }

    @Override
    public void send(Message message, ProcessingContext context, ScopeDescriptor scopeDescription) throws Exception {
        if (!(message instanceof EventMessage eventMessage)) {
            String exceptionMessage = String.format(
                    "Something else than an EventMessage was scheduled for Saga of type [%s], "
                            + "whilst Sagas can only handle EventMessages.",
                    getSagaType()
            );
            throw new IllegalArgumentException(exceptionMessage);
        }

        if (canResolve(scopeDescription)) {
            String sagaIdentifier = ((SagaScopeDescriptor) scopeDescription).getIdentifier().toString();
            Saga<T> saga = sagaRepository.load(sagaIdentifier, context);
            if (saga != null) {
                FutureUtils.joinAndUnwrap(saga.handle(eventMessage, context).asCompletableFuture());
            } else {
                logger.debug("Saga (with id: [{}]) cannot be loaded, as it most likely already ended."
                                     + " Hence, message [{}] cannot be handled.", sagaIdentifier, message);
            }
        }
    }

    @Override
    public boolean canResolve(ScopeDescriptor scopeDescription) {
        return scopeDescription instanceof SagaScopeDescriptor
                && Objects.equals(sagaType.getSimpleName(), ((SagaScopeDescriptor) scopeDescription).getType());
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("sagaType", sagaType);
        descriptor.describeProperty("sagaRepository", sagaRepository);
    }

    /**
     * Abstract Builder class to instantiate {@link AbstractSagaManager} implementations.
     * <p>
     * The {@code sagaFactory} is defaulted to a {@code sagaType.newInstance()} call throwing a
     * {@link SagaInstantiationException} if it fails. The {@link SagaRepository} and {@code sagaType} are
     * <b>hard requirements</b> and as such should be provided.
     *
     * @param <T> a generic specifying the Saga type managed by this implementation
     */
    public abstract static class Builder<T> {

        private SagaRepository<T> sagaRepository;
        protected Class<T> sagaType;
        private Supplier<T> sagaFactory = () -> newInstance(sagaType);

        private static <T> T newInstance(Class<T> type) {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new SagaInstantiationException("Exception while trying to instantiate a new Saga", e);
            }
        }

        /**
         * Sets the {@link SagaRepository} of generic type {@code T} used to save and load Saga instances.
         *
         * @param sagaRepository a {@link SagaRepository} of generic type {@code T} used to save and load Saga
         *                       instances
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> sagaRepository(SagaRepository<T> sagaRepository) {
            assertNonNull(sagaRepository, "SagaRepository may not be null");
            this.sagaRepository = sagaRepository;
            return this;
        }

        /**
         * Sets the {@code sagaType} as a {@link Class} managed by this instance.
         *
         * @param sagaType the {@link Class} specifying the type of Saga managed by this instance
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> sagaType(Class<T> sagaType) {
            assertNonNull(sagaType, "The sagaType may not be null");
            this.sagaType = sagaType;
            return this;
        }

        /**
         * Sets the {@code sagaFactory} of type {@link Supplier} responsible for creating new Saga instances. Defaults
         * to a {@code sagaType.newInstance()} call throwing a {@link SagaInstantiationException} if it fails.
         *
         * @param sagaFactory a {@link Supplier} of Saga type {@code T} responsible for creating new Saga instances
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> sagaFactory(Supplier<T> sagaFactory) {
            assertNonNull(sagaFactory, "The sagaFactory may not be null");
            this.sagaFactory = sagaFactory;
            return this;
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            assertNonNull(sagaRepository, "The SagaRepository is a hard requirement and should be provided");
            assertNonNull(sagaType, "The sagaType is a hard requirement and should be provided");
        }
    }
}
