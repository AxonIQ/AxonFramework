/*
 * Copyright (c) 2010-2025. Axon Framework
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

package org.axonframework.axonserver.connector.event.axon;

import com.google.protobuf.ByteString;
import io.axoniq.axonserver.connector.event.AggregateEventStream;
import io.axoniq.axonserver.connector.event.AppendEventsTransaction;
import io.axoniq.axonserver.connector.event.EventChannel;
import io.axoniq.axonserver.connector.event.EventStream;
import io.axoniq.axonserver.grpc.event.Event;
import io.grpc.Status;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.axonserver.connector.util.GrpcMetaDataConverter;
import org.axonframework.common.Assert;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.BuilderUtils;
import org.axonframework.common.StringUtils;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.stream.BlockingStream;
import org.axonframework.eventhandling.DefaultEventBusSpanFactory;
import org.axonframework.eventhandling.DomainEventData;
import org.axonframework.eventhandling.DomainEventMessage;
import org.axonframework.eventhandling.EventBusSpanFactory;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.GenericDomainEventMessage;
import org.axonframework.eventhandling.GlobalSequenceTrackingToken;
import org.axonframework.eventhandling.TrackedEventData;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.eventhandling.TrackingEventStream;
import org.axonframework.eventhandling.TrackingToken;
import org.axonframework.eventsourcing.CachingEventSourcingRepository;
import org.axonframework.eventsourcing.EventStreamUtils;
import org.axonframework.eventsourcing.eventstore.AbstractEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.AbstractEventStore;
import org.axonframework.eventsourcing.eventstore.DomainEventStream;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStoreException;
import org.axonframework.eventsourcing.snapshotting.SnapshotFilter;
import org.axonframework.messaging.StreamableMessageSource;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.monitoring.MessageMonitor;
import org.axonframework.monitoring.NoOpMessageMonitor;
import org.axonframework.serialization.SerializedObject;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.upcasting.event.EventUpcaster;
import org.axonframework.serialization.upcasting.event.NoOpEventUpcaster;
import org.axonframework.serialization.xml.XStreamSerializer;
import org.axonframework.tracing.NoOpSpanFactory;
import org.axonframework.tracing.SpanFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Spliterators;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;

import static java.util.Spliterator.*;
import static org.axonframework.common.BuilderUtils.assertNonEmpty;
import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.common.ObjectUtils.getOrDefault;

/**
 * Axon EventStore implementation that connects to the AxonIQ AxonServer Server to store and retrieve Events.
 *
 * @author Zoltan Altfatter
 * @author Marc Gathier
 * @author Allard Buijze
 * @since 4.0
 */
public class AxonServerEventStore extends AbstractEventStore {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /**
     * Instantiate a Builder to be able to create a {@link AxonServerEventStore}.
     * <p>
     * The main goal of this Builder is to instantiate an AxonServer specific {@link EventStorageEngine}. The properties
     * which may be provided through this Builder are thus all used to end up with that EventStorageEngine
     * implementation. An EventStorageEngine may be provided directly however, although we encourage the usage of the
     * {@link Builder#configuration} and {@link Builder#axonServerConnectionManager} functions to let it be created.
     * <p>
     * The {@link EventUpcaster} is defaulted to a {@link NoOpEventUpcaster} and the {@link EventBusSpanFactory} is
     * defaulted to a {@link org.axonframework.eventhandling.DefaultEventBusSpanFactory} backed by a
     * {@link org.axonframework.tracing.NoOpSpanFactory}.
     * <p>
     * The event and snapshot {@link Serializer}, {@link AxonServerConfiguration} and
     * {@link AxonServerConnectionManager} are <b>hard requirements</b> if no EventStorageEngine is provided directly.
     *
     * @return a Builder to be able to create a {@link AxonServerEventStore}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Instantiate a {@link AxonServerEventStore} based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the {@link EventStorageEngine} is set. If not, the {@link AxonServerConfiguration} and {@link
     * AxonServerConnectionManager} should minimally be provided to create an AxonServer specific EventStorageEngine
     * implementation. If either of these {@code null} assertions fail, an {@link AxonConfigurationException} will be
     * thrown.
     *
     * @param builder the {@link Builder} used to instantiate a {@link AxonServerEventStore} instance
     */
    protected AxonServerEventStore(Builder builder) {
        super(builder);
    }

    @Override
    public TrackingEventStream openStream(TrackingToken trackingToken) {
        return storageEngine().openStream(trackingToken);
    }

    public QueryResultStream query(String query, boolean liveUpdates) {
        return storageEngine().query(query, liveUpdates);
    }

    @Override
    protected AxonIQEventStorageEngine storageEngine() {
        return (AxonIQEventStorageEngine) super.storageEngine();
    }

    /**
     * Creates a {@link StreamableMessageSource} that streams events from the given {@code context}, rather than the
     * default context the application was configured to connect to.
     *
     * @param context the name of the context to create a message source for
     * @return a {@link StreamableMessageSource} of {@link TrackedEventMessage}s tied to the given {@code context}
     */
    public StreamableMessageSource<TrackedEventMessage<?>> createStreamableMessageSourceForContext(String context) {
        return new AxonServerMessageSource(storageEngine().createInstanceForContext(context));
    }

    @Override
    protected Optional<DomainEventMessage<?>> handleSnapshotReadingError(String aggregateIdentifier, Throwable e) {
        if (Status.fromThrowable(e).getCode() != Status.Code.UNKNOWN) {
            throw new EventStoreException("Error occurred while communicating with Axon Server", e);
        }
        return super.handleSnapshotReadingError(aggregateIdentifier, e);
    }

    @Override
    public DomainEventStream readEvents(String aggregateIdentifier) {
        if (!storageEngine().snapshotFilterSet && Objects.equals(storageEngine().eventSerializer, storageEngine().snapshotSerializer)) {
            return storageEngine().readEventsWithAutoSnapshot(aggregateIdentifier, storageEngine().eventSerializer);
        }
        return super.readEvents(aggregateIdentifier);
    }

    /**
     * Builder class to instantiate a {@link AxonServerEventStore}.
     * <p>
     * The main goal of this Builder is to instantiate an AxonServer specific {@link EventStorageEngine}. The properties
     * which may be provided through this Builder are thus all used to end up with that EventStorageEngine
     * implementation. An EventStorageEngine may be provided directly however, although we encourage the usage of the
     * {@link Builder#configuration} and {@link Builder#axonServerConnectionManager} functions to let it be created.
     * <p>
     * The {@link EventUpcaster} is defaulted to a {@link NoOpEventUpcaster}. The {@link MessageMonitor} is defaulted to
     * an {@link NoOpMessageMonitor} and the {@link EventBusSpanFactory} defaults to a
     * {@link DefaultEventBusSpanFactory} backed by a {@link NoOpSpanFactory}.
     * <p>
     * The event and snapshot {@link Serializer}, {@link AxonServerConfiguration} and
     * {@link AxonServerConnectionManager} are <b>hard requirements</b> if no EventStorageEngine is provided directly.
     */
    public static class Builder extends AbstractEventStore.Builder {

        private AxonServerConfiguration configuration;
        private AxonServerConnectionManager axonServerConnectionManager;
        private Supplier<Serializer> snapshotSerializer;
        private Supplier<Serializer> eventSerializer;
        private EventUpcaster upcasterChain = NoOpEventUpcaster.INSTANCE;
        private SnapshotFilter snapshotFilter;
        private String defaultContext;

        @Override
        public Builder storageEngine(EventStorageEngine storageEngine) {
            super.storageEngine(storageEngine);
            return this;
        }

        @Override
        public Builder messageMonitor(@Nonnull MessageMonitor<? super EventMessage<?>> messageMonitor) {
            super.messageMonitor(messageMonitor);
            return this;
        }

        @Override
        public Builder spanFactory(@Nonnull SpanFactory spanFactory) {
            super.spanFactory(spanFactory);
            return this;
        }

        @Override
        public Builder spanFactory(@Nonnull EventBusSpanFactory spanFactory) {
            super.spanFactory(spanFactory);
            return this;
        }

        /**
         * Sets the {@link AxonServerConfiguration} describing the servers to connect with and how to manage flow
         * control.
         * <p>
         * This object is used by the AxonServer {@link EventStorageEngine} implementation which this Builder will
         * create if it is not provided. We suggest to use these, instead of the {@link Builder#storageEngine()}
         * function to set the EventStorageEngine.
         *
         * @param configuration the {@link AxonServerConfiguration} describing the servers to connect with and how to
         *                      manage flow control
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder configuration(AxonServerConfiguration configuration) {
            assertNonNull(configuration, "AxonServerConfiguration may not be null");
            this.configuration = configuration;
            return this;
        }

        /**
         * Sets the {@link AxonServerConnectionManager} managing the connections to the AxonServer platform.
         * <p>
         * This object is used by the AxonServer {@link EventStorageEngine} implementation which this Builder will
         * create if it is not provided. We suggest to use these, instead of the {@link Builder#storageEngine()}
         * function to set the EventStorageEngine.
         *
         * @param axonServerConnectionManager the {@link AxonServerConnectionManager} managing the connections to the
         *                                    AxonServer platform
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder platformConnectionManager(AxonServerConnectionManager axonServerConnectionManager) {
            assertNonNull(axonServerConnectionManager, "PlatformConnectionManager may not be null");
            this.axonServerConnectionManager = axonServerConnectionManager;
            return this;
        }

        /**
         * Sets the {@link Serializer} used to serialize and deserialize snapshots. Defaults to a {@link
         * XStreamSerializer}.
         * <p>
         * This object is used by the AxonServer {@link EventStorageEngine} implementation which this Builder will
         * create if it is not provided. We suggest to use these, instead of the {@link Builder#storageEngine()}
         * function to set the EventStorageEngine.
         *
         * @param snapshotSerializer a {@link Serializer} used to serialize and deserialize snapshots
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder snapshotSerializer(Serializer snapshotSerializer) {
            assertNonNull(snapshotSerializer, "The Snapshot Serializer may not be null");
            this.snapshotSerializer = () -> snapshotSerializer;
            return this;
        }

        /**
         * Sets the {@link Serializer} used to serialize and deserialize the Event Message's payload and Meta Data with.
         * Defaults to a {@link XStreamSerializer}.
         * <p>
         * This object is used by the AxonServer {@link EventStorageEngine} implementation which this Builder will
         * create if it is not provided. We suggest to use these, instead of the {@link Builder#storageEngine()}
         * function to set the EventStorageEngine.
         *
         * @param eventSerializer The serializer to serialize the Event Message's payload and Meta Data with
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder eventSerializer(Serializer eventSerializer) {
            assertNonNull(eventSerializer, "The Event Serializer may not be null");
            this.eventSerializer = () -> eventSerializer;
            return this;
        }

        /**
         * Sets the default context for this event store to connect to.
         *
         * @param defaultContext for this event store to connect to.
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder defaultContext(String defaultContext) {
            assertNonEmpty(defaultContext, "The default context may not be null");
            this.defaultContext = defaultContext;
            return this;
        }

        /**
         * Sets the {@link Predicate} used to filter snapshots when returning aggregate events. When not set all
         * snapshots are used.
         * <p>
         * This object is used by the AxonServer {@link EventStorageEngine} implementation.
         *
         * @param snapshotFilter The snapshot filter predicate
         * @return the current Builder instance, for fluent interfacing
         * @deprecated in favor of {@link #snapshotFilter(SnapshotFilter)}
         */
        @Deprecated
        public Builder snapshotFilter(Predicate<? super DomainEventData<?>> snapshotFilter) {
            return snapshotFilter(snapshotFilter::test);
        }

        /**
         * Sets the {@link SnapshotFilter} used to filter snapshots when returning aggregate events. When not set all
         * snapshots are used. Note that {@link SnapshotFilter} instances can be combined and should return {@code true}
         * if they handle a snapshot they wish to ignore.
         * <p>
         * This object is used by the AxonServer {@link EventStorageEngine} implementation.
         *
         * @param snapshotFilter the {@link SnapshotFilter} to use
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder snapshotFilter(SnapshotFilter snapshotFilter) {
            assertNonNull(snapshotFilter, "The Snapshot filter may not be null");
            this.snapshotFilter = snapshotFilter;
            return this;
        }

        /**
         * Sets the {@link EventUpcaster} used to deserialize events of older revisions. Defaults to a {@link
         * NoOpEventUpcaster}.
         * <p>
         * This object is used by the AxonServer {@link EventStorageEngine} implementation which this Builder will
         * create if it is not provided. We suggest to use these, instead of the {@link Builder#storageEngine()}
         * function to set the EventStorageEngine.
         *
         * @param upcasterChain an {@link EventUpcaster} used to deserialize events of older revisions
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder upcasterChain(EventUpcaster upcasterChain) {
            assertNonNull(upcasterChain, "EventUpcaster may not be null");
            this.upcasterChain = upcasterChain;
            return this;
        }

        /**
         * Initializes a {@link AxonServerEventStore} as specified through this Builder.
         *
         * @return a {@link AxonServerEventStore} as specified through this Builder
         */
        public AxonServerEventStore build() {
            if (storageEngine == null) {
                buildStorageEngine();
            }
            return new AxonServerEventStore(this);
        }

        private void buildStorageEngine() {
            if (snapshotSerializer == null) {
                snapshotSerializer = XStreamSerializer::defaultSerializer;
            }
            if (eventSerializer == null) {
                eventSerializer = XStreamSerializer::defaultSerializer;
            }

            assertNonNull(configuration, "The AxonServerConfiguration is a hard requirement and should be provided");
            assertNonNull(axonServerConnectionManager,
                          "The PlatformConnectionManager is a hard requirement and should be provided");

            super.storageEngine(AxonIQEventStorageEngine.builder()
                                                        .snapshotSerializer(snapshotSerializer.get())
                                                        .upcasterChain(upcasterChain)
                                                        .snapshotFilter(snapshotFilter)
                                                        .eventSerializer(eventSerializer.get())
                                                        .configuration(configuration)
                                                        .defaultContext(defaultContext)
                                                        .eventStoreClient(axonServerConnectionManager)
                                                        .converter(new GrpcMetaDataConverter(eventSerializer.get()))
                                                        .build());
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        @Override
        protected void validate() throws AxonConfigurationException {
            BuilderUtils.assertNonNull(snapshotFilter, "The SnapshotFilter is a hard requirement and should be provided");
            super.validate();
        }
    }

    static class AxonIQEventStorageEngine extends AbstractEventStorageEngine {

        private static final int ALLOW_SNAPSHOTS_MAGIC_VALUE = -42;
        private final String APPEND_EVENT_TRANSACTION = this + "/APPEND_EVENT_TRANSACTION";
        private final String LAST_MESSAGE_ID_KEY_PREFIX = this + "/LastMessageId/";
        private final String PREDICTED_SEQUENCE_KEY_PREFIX = this + "/PredictedSequence/";
        private static final boolean WITHOUT_SNAPSHOTS = false;

        private final AxonServerConfiguration configuration;
        private final AxonServerConnectionManager connectionManager;
        private final GrpcMetaDataConverter converter;
        private final boolean snapshotFilterSet;
        private final Serializer snapshotSerializer;
        private final Serializer eventSerializer;

        private final Builder builder;
        private final String context;

        private static Builder builder() {
            return new Builder();
        }

        private AxonIQEventStorageEngine(Builder builder) {
            this(builder, StringUtils.nonEmptyOrNull(builder.defaultContext) ? builder.defaultContext : builder.configuration.getContext());
        }

        private AxonIQEventStorageEngine(Builder builder, String context) {
            super(builder);
            this.snapshotFilterSet = builder.snapshotFilterSet;
            this.configuration = builder.configuration;
            this.connectionManager = builder.connectionManager;
            this.converter = builder.converter;

            this.builder = builder;
            this.context = context;

            this.snapshotSerializer = new GrpcMetaDataAwareSerializer(super.getSnapshotSerializer());
            this.eventSerializer = new GrpcMetaDataAwareSerializer(super.getEventSerializer());
        }

        /**
         * Creates a new AxonIQEventStorageEngine instance specifically for the given {@code context}. Can be used to
         * create multiple message sources per context if an application is required to handle events from several
         * Bounded Contexts.
         *
         * @param context a {@link String} defining the context to create an AxonIQEventStorageEngine instance
         * @return an AxonIQEventStorageEngine within the given {@code context}
         */
        private AxonIQEventStorageEngine createInstanceForContext(String context) {
            return new AxonIQEventStorageEngine(this.builder, context);
        }

        @Override
        protected void appendEvents(List<? extends EventMessage<?>> events, Serializer serializer) {
            AppendEventsTransaction sender;
            if (CurrentUnitOfWork.isStarted()) {
                sender = CurrentUnitOfWork.get().root().getOrComputeResource(APPEND_EVENT_TRANSACTION, k -> {
                    AppendEventsTransaction appendEventTransaction =
                            connectionManager.getConnection(context).eventChannel().startAppendEventsTransaction();
                    CurrentUnitOfWork.get().root().onRollback(
                            u -> appendEventTransaction.rollback()
                    );
                    CurrentUnitOfWork.get().root().onCommit(u -> commit(appendEventTransaction));
                    return appendEventTransaction;
                });
            } else {
                sender = connectionManager.getConnection(context).eventChannel().startAppendEventsTransaction();
            }
            for (EventMessage<?> eventMessage : events) {
                sender.appendEvent(map(eventMessage, serializer));
                if (CurrentUnitOfWork.isStarted() && eventMessage instanceof DomainEventMessage) {
                    registerCommittedSequenceSupplier((DomainEventMessage<?>) eventMessage);
                }
            }
            if (!CurrentUnitOfWork.isStarted()) {
                commit(sender);
            }
        }

        /**
         * Called for each {@link DomainEventMessage} appended in the current transaction. Keeps track of the last
         * event's identifier and locally-predicted sequence number per aggregate, and lazily registers a
         * {@link Supplier} under {@link CachingEventSourcingRepository#COMMITTED_SEQUENCE_SUPPLIER_KEY_PREFIX} in the
         * root UnitOfWork. The supplier is called by {@link CachingEventSourcingRepository} after commit to obtain
         * the actual server-assigned sequence number for the cached aggregate entry.
         */
        private void registerCommittedSequenceSupplier(DomainEventMessage<?> event) {
            String aggregateId = event.getAggregateIdentifier();
            AtomicReference<String> lastIdRef = CurrentUnitOfWork.get().root()
                    .getOrComputeResource(LAST_MESSAGE_ID_KEY_PREFIX + aggregateId, k -> new AtomicReference<>());
            lastIdRef.set(event.getIdentifier());
            AtomicLong predictedSeqRef = CurrentUnitOfWork.get().root()
                    .getOrComputeResource(PREDICTED_SEQUENCE_KEY_PREFIX + aggregateId, k -> new AtomicLong());
            predictedSeqRef.set(event.getSequenceNumber());
            CurrentUnitOfWork.get().root()
                    .<Supplier<Optional<Long>>>getOrComputeResource(
                            CachingEventSourcingRepository.COMMITTED_SEQUENCE_SUPPLIER_KEY_PREFIX + aggregateId,
                            k -> () -> resolveCommittedSequence(aggregateId, lastIdRef, predictedSeqRef)
                    );
        }

        /**
         * Resolves the sequence number that was actually assigned to the last event committed for the given aggregate.
         * <p>
         * First asks the server for the current highest sequence number. If it matches the locally-predicted value,
         * no concurrent commit occurred and the sequence is returned immediately (one roundtrip). If the value
         * differs — as happens in DCB contexts where the server assigns global rather than per-aggregate sequence
         * numbers — the event at that position is read and its message identifier is compared against the last event
         * we sent. A match confirms the sequence belongs to our transaction; a mismatch indicates a concurrent commit
         * and {@link Optional#empty()} is returned to signal that caching should be skipped.
         */
        private Optional<Long> resolveCommittedSequence(String aggregateId,
                                                        AtomicReference<String> expectedLastIdRef,
                                                        AtomicLong predictedSeqRef) {
            try {
                Optional<Long> lastSeq = lastSequenceNumberFor(aggregateId);
                if (!lastSeq.isPresent()) {
                    return Optional.empty();
                }
                long sequence = lastSeq.get();
                if (sequence == predictedSeqRef.get()) {
                    // Sequence matches the locally-predicted value; no concurrent commit occurred.
                    return Optional.of(sequence);
                }
                // Sequence differs from prediction (DCB global sequences, or a concurrent commit).
                // Read the event at that position and verify it belongs to our transaction.
                AggregateEventStream stream = connectionManager.getConnection(context)
                                                               .eventChannel()
                                                               .openAggregateStream(aggregateId, sequence, sequence);
                try {
                    if (stream.hasNext() && expectedLastIdRef.get().equals(stream.next().getMessageIdentifier())) {
                        return Optional.of(sequence);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    stream.cancel();
                }
            } catch (Exception e) {
                logger.warn("Failed to resolve committed sequence for aggregate [{}]", aggregateId, e);
            }
            return Optional.empty();
        }

        private void commit(AppendEventsTransaction appendEventTransaction) {
            try {
                appendEventTransaction.commit().get(configuration.getCommitTimeout(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                }
                throw new EventStoreException(e.getMessage(), e.getCause());
            } catch (TimeoutException e) {
                throw new org.axonframework.messaging.ExecutionException("Timeout while executing request", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EventStoreException(e.getMessage(), e);
            }
        }

        public Event map(EventMessage<?> eventMessage, Serializer serializer) {
            Event.Builder builder = Event.newBuilder();
            if (eventMessage instanceof GenericDomainEventMessage) {
                builder.setAggregateIdentifier(((GenericDomainEventMessage<?>) eventMessage).getAggregateIdentifier())
                       .setAggregateSequenceNumber(((GenericDomainEventMessage<?>) eventMessage).getSequenceNumber())
                       .setAggregateType(((GenericDomainEventMessage<?>) eventMessage).getType());
            }
            SerializedObject<byte[]> serializedPayload = eventMessage.serializePayload(serializer, byte[].class);
            builder.setMessageIdentifier(eventMessage.getIdentifier()).setPayload(
                    io.axoniq.axonserver.grpc.SerializedObject.newBuilder()
                                                              .setType(serializedPayload.getType().getName())
                                                              .setRevision(getOrDefault(
                                                                      serializedPayload.getType().getRevision(),
                                                                      ""
                                                              ))
                                                              .setData(ByteString.copyFrom(serializedPayload.getData()))
            ).setTimestamp(eventMessage.getTimestamp().toEpochMilli());
            eventMessage.getMetaData().forEach((k, v) -> builder.putMetaData(k, converter.convertToMetaDataValue(v)));
            return builder.build();
        }


        @Override
        protected void storeSnapshot(DomainEventMessage<?> snapshot, Serializer serializer) {
            connectionManager.getConnection(context)
                             .eventChannel()
                             .appendSnapshot(map(snapshot, serializer))
                             .whenComplete((c, e) -> {
                                 if (e != null) {
                                     logger.warn("Error occurred while appending a snapshot for aggregate [{}] with [{}].",
                                                 snapshot.getType(), snapshot.getAggregateIdentifier(), e);
                                 } else if (c != null) {
                                     if (c.getSuccess()) {
                                         logger.debug("Snapshot created for aggregate type {}, identifier {}",
                                                      snapshot.getType(), snapshot.getAggregateIdentifier());
                                     } else {
                                         logger.warn("Snapshot creation failed for unknown reason. Check server logs for details.");
                                     }
                                 }
                             });
        }

        @Override
        protected Stream<? extends DomainEventData<?>> readEventData(String aggregateIdentifier,
                                                                     long firstSequenceNumber) {
            logger.debug("Reading events for aggregate id {}", aggregateIdentifier);
            EventChannel eventChannel = connectionManager.getConnection(context).eventChannel();

            AggregateEventStream aggregateStream;
            if (firstSequenceNumber >= 0) {
                aggregateStream = eventChannel.openAggregateStream(aggregateIdentifier, firstSequenceNumber);
            } else if (firstSequenceNumber == ALLOW_SNAPSHOTS_MAGIC_VALUE && !snapshotFilterSet) {
                aggregateStream = eventChannel.openAggregateStream(aggregateIdentifier);
            } else {
                aggregateStream = eventChannel.openAggregateStream(aggregateIdentifier, WITHOUT_SNAPSHOTS);
            }

            return aggregateStream.asStream().map(GrpcBackedDomainEventData::new);
        }

        public TrackingEventStream openStream(TrackingToken trackingToken) {
            Assert.isTrue(
                    trackingToken == null || trackingToken instanceof GlobalSequenceTrackingToken,
                    () -> String.format("Token [%s] is of the wrong type. Expected [%s]",
                                        trackingToken, GlobalSequenceTrackingToken.class.getSimpleName())
            );
            long nextToken = trackingToken == null
                    ? -1
                    : ((GlobalSequenceTrackingToken) trackingToken).getGlobalIndex();

            EventStream stream = connectionManager.getConnection(context)
                                                  .eventChannel()
                                                  .openStream(
                                                          nextToken,
                                                          configuration.getEventFlowControl().getPermits(),
                                                          configuration.getEventFlowControl().getNrOfNewPermits(),
                                                          configuration.isForceReadFromLeader()
                                                  );

            return new EventBuffer(stream, upcasterChain, eventSerializer, !configuration.isEventBlockListingEnabled());
        }

        public QueryResultStream query(String query, boolean liveUpdates) {
            return new QueryResultStreamAdapter(
                    connectionManager.getConnection(context)
                                     .eventChannel()
                                     .queryEvents(query, liveUpdates)
            );
        }

        public DomainEventStream readEventsWithAutoSnapshot(@Nonnull String aggregateIdentifier, Serializer serializer) {
            Stream<? extends DomainEventData<?>> input =
                    this.readEventData(aggregateIdentifier, ALLOW_SNAPSHOTS_MAGIC_VALUE);

            return EventStreamUtils.upcastAndDeserializeDomainEvents(input, serializer, upcasterChain);
        }

        @Override
        public Optional<Long> lastSequenceNumberFor(@Nonnull String aggregateIdentifier) {
            try {
                Long lastSequenceNumber =
                        connectionManager.getConnection(context)
                                         .eventChannel()
                                         .findHighestSequence(aggregateIdentifier)
                                         .get(configuration.getCommitTimeout(), TimeUnit.MILLISECONDS);
                return lastSequenceNumber == null || lastSequenceNumber < 0
                        ? Optional.empty()
                        : Optional.of(lastSequenceNumber);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EventStoreException(e.getMessage(), e);
            } catch (TimeoutException | ExecutionException e) {
                throw new EventStoreException(e.getMessage(), e);
            }
        }

        @Override
        public TrackingToken createTailToken() {
            try {
                Long token = connectionManager.getConnection(context)
                                              .eventChannel()
                                              .getFirstToken()
                                              .get(configuration.getCommitTimeout(), TimeUnit.MILLISECONDS);
                return token == null || token < 0 ? null : new GlobalSequenceTrackingToken(token);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EventStoreException(e.getMessage(), e);
            } catch (TimeoutException | ExecutionException e) {
                throw new EventStoreException(e.getMessage(), e);
            }
        }

        @Override
        public TrackingToken createHeadToken() {
            try {
                Long token = connectionManager.getConnection(context)
                                              .eventChannel()
                                              .getLastToken()
                                              .get(configuration.getCommitTimeout(), TimeUnit.MILLISECONDS);
                return token == null || token < 0 ? null : new GlobalSequenceTrackingToken(token);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EventStoreException(e.getMessage(), e);
            } catch (TimeoutException | ExecutionException e) {
                throw new EventStoreException(e.getMessage(), e);
            }
        }

        @Override
        public TrackingToken createTokenAt(@Nonnull Instant instant) {
            try {
                Long token = connectionManager.getConnection(context)
                                              .eventChannel()
                                              .getTokenAt(instant.toEpochMilli())
                                              .get(configuration.getCommitTimeout(), TimeUnit.MILLISECONDS);
                return token == null || token < 0 ? null : new GlobalSequenceTrackingToken(token);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EventStoreException(e.getMessage(), e);
            } catch (TimeoutException | ExecutionException e) {
                throw new EventStoreException(e.getMessage(), e);
            }
        }

        @Override
        protected Stream<? extends TrackedEventData<?>> readEventData(TrackingToken trackingToken, boolean mayBlock) {
            throw new UnsupportedOperationException(
                    "This method is not optimized for the AxonIQ Event Store and should not be used"
            );
        }

        @Override
        protected Stream<? extends DomainEventData<?>> readSnapshotData(String aggregateIdentifier) {
            return StreamSupport.stream(new Spliterators.AbstractSpliterator<DomainEventData<?>>(
                    Long.MAX_VALUE, NONNULL | ORDERED | DISTINCT | CONCURRENT
            ) {
                private long sequenceNumber = Long.MAX_VALUE;
                private final List<DomainEventData<byte[]>> prefetched = new ArrayList<>();

                @Override
                public boolean tryAdvance(Consumer<? super DomainEventData<?>> action) {
                    if (prefetched.isEmpty() && sequenceNumber >= 0) {
                        connectionManager.getConnection(context)
                                         .eventChannel()
                                         .loadSnapshots(aggregateIdentifier,
                                                        sequenceNumber,
                                                        configuration.getSnapshotPrefetch())
                                         .asStream()
                                         .map(GrpcBackedDomainEventData::new)
                                         .forEach(prefetched::add);
                    }

                    if (prefetched.isEmpty()) {
                        return false;
                    }

                    DomainEventData<?> snapshot = prefetched.remove(0);
                    sequenceNumber = snapshot.getSequenceNumber() - 1;
                    action.accept(snapshot);
                    return true;
                }
            }, false);
        }

        @Override
        public Serializer getSnapshotSerializer() {
            return this.snapshotSerializer;
        }

        @Override
        public Serializer getEventSerializer() {
            return this.eventSerializer;
        }

        private static class Builder extends AbstractEventStorageEngine.Builder {

            private boolean snapshotFilterSet;
            private AxonServerConfiguration configuration;
            private AxonServerConnectionManager connectionManager;
            private GrpcMetaDataConverter converter;
            private String defaultContext;

            @Override
            public Builder snapshotSerializer(Serializer snapshotSerializer) {
                if (snapshotSerializer != null) {
                    super.snapshotSerializer(snapshotSerializer);
                }
                return this;
            }

            @Override
            public Builder upcasterChain(EventUpcaster upcasterChain) {
                if (upcasterChain != null) {
                    super.upcasterChain(upcasterChain);
                }
                return this;
            }

            @Override
            public Builder persistenceExceptionResolver(PersistenceExceptionResolver persistenceExceptionResolver) {
                super.persistenceExceptionResolver(persistenceExceptionResolver);
                return this;
            }

            @Override
            public Builder eventSerializer(Serializer eventSerializer) {
                if (eventSerializer != null) {
                    super.eventSerializer(eventSerializer);
                }
                return this;
            }

            /**
             * {@inheritDoc}
             *
             * @deprecated in favor of {@link #snapshotFilter(SnapshotFilter)}
             */
            @Override
            @Deprecated
            public Builder snapshotFilter(Predicate<? super DomainEventData<?>> snapshotFilter) {
                if (snapshotFilter != null) {
                    super.snapshotFilter(snapshotFilter);
                    snapshotFilterSet = true;
                }
                return this;
            }

            @Override
            public Builder snapshotFilter(SnapshotFilter snapshotFilter) {
                if (snapshotFilter != null) {
                    super.snapshotFilter(snapshotFilter);
                    snapshotFilterSet = true;
                }
                return this;
            }

            private Builder configuration(AxonServerConfiguration configuration) {
                assertNonNull(configuration, "AxonServerConfiguration may not be null");
                this.configuration = configuration;
                return this;
            }


            private Builder defaultContext(String defaultContext) {
                this.defaultContext = defaultContext;
                return this;
            }

            private Builder eventStoreClient(AxonServerConnectionManager eventStoreClient) {
                assertNonNull(eventStoreClient, "AxonServerEventStoreClient may not be null");
                this.connectionManager = eventStoreClient;
                return this;
            }

            private Builder converter(GrpcMetaDataConverter converter) {
                assertNonNull(converter, "GrpcMetaDataConverter may not be null");
                this.converter = converter;
                return this;
            }

            private AxonIQEventStorageEngine build() {
                return new AxonIQEventStorageEngine(this);
            }

            @Override
            protected void validate() throws AxonConfigurationException {
                assertNonNull(configuration,
                              "The AxonServerConfiguration is a hard requirement and should be provided");
                assertNonNull(connectionManager,
                              "The AxonServerEventStoreClient is a hard requirement and should be provided");
                assertNonNull(converter, "The GrpcMetaDataConverter is a hard requirement and should be provided");
            }
        }
    }

    /**
     * Wrapper around an {@link AxonIQEventStorageEngine} serving as a {@link StreamableMessageSource} of type {@link
     * TrackedEventMessage}, delegating the calls towards the provided storage engine. Can be leveraged to create new
     * StreamableMessageSources, each delegating towards a storage engine within a different Bounded Context.
     */
    private static class AxonServerMessageSource implements StreamableMessageSource<TrackedEventMessage<?>> {

        private final AxonIQEventStorageEngine eventStorageEngine;

        AxonServerMessageSource(AxonIQEventStorageEngine eventStorageEngine) {
            this.eventStorageEngine = eventStorageEngine;
        }

        @Override
        public BlockingStream<TrackedEventMessage<?>> openStream(TrackingToken trackingToken) {
            return eventStorageEngine.openStream(trackingToken);
        }

        @Override
        public TrackingToken createTailToken() {
            return eventStorageEngine.createTailToken();
        }

        @Override
        public TrackingToken createHeadToken() {
            return eventStorageEngine.createHeadToken();
        }

        @Override
        public TrackingToken createTokenAt(Instant dateTime) {
            return eventStorageEngine.createTokenAt(dateTime);
        }
    }
}
