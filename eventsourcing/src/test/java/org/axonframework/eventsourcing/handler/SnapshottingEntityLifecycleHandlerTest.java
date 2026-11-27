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

package org.axonframework.eventsourcing.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.test.appender.ListAppender;
import org.apache.logging.log4j.message.Message;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.Converter;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.eventstore.AnnotationBasedTagResolver;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.GlobalIndexPositions;
import org.axonframework.eventsourcing.eventstore.Position;
import org.axonframework.eventsourcing.eventstore.SnapshotCapableEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.api.SnapshotCandidate;
import org.axonframework.eventsourcing.snapshot.api.SnapshotMatch;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.VersionedType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.repository.ManagedEntity;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link SnapshottingEntityLifecycleHandler}.
 *
 * @author John Hendrikx
 */
class SnapshottingEntityLifecycleHandlerTest {

    private record AccountCreated(@EventTag(key = "account") String id, String name) {}
    private record FundsDeposited(@EventTag(key = "account") String id, long amount) {}
    private record AccountClosed(@EventTag(key = "account") String id) {}
    private record Account(String id, String name, long balance) {}

    private static final String ACCOUNT_ID = "account-1";
    private static final VersionedType ACCOUNT_TYPE = VersionedType.of("Account");

    private static final Converter CONVERTER = new Converter() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T convert(Object input, Type targetType) {
            if (targetType instanceof Class<?> cls && cls.isInstance(input)) {
                return (T) input;
            }
            throw new RuntimeException("Cannot convert " + input.getClass() + " to " + targetType);
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {}
    };

    private final AtomicInteger evolveCalls = new AtomicInteger();
    private final InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
    private final StorageEngineBackedEventStore eventStore = new StorageEngineBackedEventStore(
        new SnapshotCapableEventStorageEngine(new InMemoryEventStorageEngine(), snapshotStore),
        new SimpleEventBus(),
        new AnnotationBasedTagResolver()
    );

    private final SnapshottingEntityLifecycleHandler<String, Account> handler = new SnapshottingEntityLifecycleHandler<>(
        eventStore,
        (id, ctx) -> EventCriteria.havingTags(Tag.of("account", id)),
        new AnnotationBasedTagResolver(),
        new InitializingEntityEvolver<>(
            (id, msg, ctx) -> {
                AccountCreated ac = (AccountCreated) msg.payload();

                return new Account(ac.id(), ac.name(), 0);
            },
            (entity, event, ctx) -> {
                evolveCalls.incrementAndGet();

                return event.payload() instanceof FundsDeposited fd
                    ? new Account(entity.id(), entity.name(), entity.balance() + fd.amount())
                    : entity;
            }
        ),
        SnapshotPolicy.afterEvents(2),
        ACCOUNT_TYPE,
        CONVERTER,
        Account.class,
        snapshotStore
    );

    private ListAppender handlerLog;

    @BeforeEach
    void beforeEach() {
        handlerLog = new ListAppender("HandlerLog");
        handlerLog.start();
        ((Logger) LogManager.getLogger(SnapshottingEntityLifecycleHandler.class)).addAppender(handlerLog);
    }

    @AfterEach
    void afterEach() {
        ((Logger) LogManager.getLogger(SnapshottingEntityLifecycleHandler.class)).removeAppender(handlerLog);
        handlerLog.stop();
    }

    @Nested
    class WhenSourcedFromEvents {

        @Test
        void reconstructsEntityFromAllEvents() {
            publish(new AccountCreated(ACCOUNT_ID, "Alice"), new FundsDeposited(ACCOUNT_ID, 100));

            Account account = source();

            assertThat(account.balance()).isEqualTo(100);
        }

        @Test
        void noWarningWhenNoSnapshotExists() {
            publish(new AccountCreated(ACCOUNT_ID, "Alice"), new FundsDeposited(ACCOUNT_ID, 100));

            handlerLog.clear();
            source();

            assertThat(handlerLog.getEvents()).isEmpty();
        }
    }

    @Nested
    class WhenSnapshotExists {

        @Test
        void startsFromSnapshotStateAndAppliesSubsequentEvents() {
            publish(new AccountCreated(ACCOUNT_ID, "Alice"));
            storeSnapshot(new Account(ACCOUNT_ID, "Alice", 500), GlobalIndexPositions.of(1));
            publish(new FundsDeposited(ACCOUNT_ID, 100));

            Account account = source();

            assertThat(account.balance()).isEqualTo(600);
        }

        @Test
        void onlyEventsAfterSnapshotPositionAreApplied() {
            publish(new AccountCreated(ACCOUNT_ID, "Alice"));
            storeSnapshot(new Account(ACCOUNT_ID, "Alice", 500), GlobalIndexPositions.of(1));
            publish(new FundsDeposited(ACCOUNT_ID, 100));

            source();

            assertThat(evolveCalls.get()).isEqualTo(1);
        }

        @Test
        void entityStateMatchesSnapshotAloneWhenNoSubsequentEventsExist() {
            publish(new AccountCreated(ACCOUNT_ID, "Alice"));
            storeSnapshot(new Account(ACCOUNT_ID, "Alice", 500), GlobalIndexPositions.of(1));

            Account account = source();

            assertThat(account.balance()).isEqualTo(500);
            assertThat(evolveCalls.get()).isZero();
        }
    }

    @Nested
    class WhenObservingLogOutput {

        @Test
        void noWarningWhenNoSnapshotExists() {
            publish(new AccountCreated(ACCOUNT_ID, "Alice"), new FundsDeposited(ACCOUNT_ID, 100));

            handlerLog.clear();
            source();

            assertThat(handlerLog.getEvents()).isEmpty();
        }

        @Test
        void shouldReconstructFullyWhenOnlyASnapshotOfAnUnacceptableVersionIsStored() {
            publish(new AccountCreated(ACCOUNT_ID, "Alice"), new FundsDeposited(ACCOUNT_ID, 100));
            storeSnapshot(new Account(ACCOUNT_ID, "Alice", 999), GlobalIndexPositions.of(2), "42.0");

            handlerLog.clear();

            Account account = source();

            assertThat(account.balance()).isEqualTo(100);

            // the store passes over a snapshot of a version that was not asked for, so the handler never sees it
            assertThat(handlerLog.getEvents())
                .extracting(LogEvent::getMessage)
                .extracting(Message::getFormattedMessage)
                .noneMatch(message -> message.startsWith("Unsupported snapshot version"));
        }

        @Test
        void shouldReconstructFullyWhenASnapshotOfAnotherNameReachesIt() {
            // given a store answering with a snapshot found under a former name, left untransformed
            QualifiedName formerName = new QualifiedName("FormerAccount");
            SnapshotStore formerNameStore = new SnapshotStore() {

                @Override
                public CompletableFuture<Void> store(QualifiedName qualifiedName, Object identifier,
                                                     Snapshot snapshot, @Nullable ProcessingContext context) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<@Nullable SnapshotMatch> load(List<SnapshotCandidate> candidates,
                                                                       Object identifier,
                                                                       @Nullable ProcessingContext context) {
                    Snapshot snapshot = new Snapshot(
                        GlobalIndexPositions.of(2), ACCOUNT_TYPE.version(), new Account(ACCOUNT_ID, "Alice", 999),
                        Instant.now(), Map.of()
                    );

                    return CompletableFuture.completedFuture(new SnapshotMatch(formerName, snapshot));
                }

                // temporary: SnapshotStore keeps this deprecated method so Axoniq Framework keeps compiling
                @Override
                public CompletableFuture<@Nullable Snapshot> load(QualifiedName qualifiedName, Object identifier,
                                                                  @Nullable ProcessingContext context) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void describeTo(ComponentDescriptor descriptor) {
                }
            };
            StorageEngineBackedEventStore store = new StorageEngineBackedEventStore(
                new SnapshotCapableEventStorageEngine(new InMemoryEventStorageEngine(), formerNameStore),
                new SimpleEventBus(),
                new AnnotationBasedTagResolver()
            );

            publish(store, new AccountCreated(ACCOUNT_ID, "Alice"), new FundsDeposited(ACCOUNT_ID, 100));
            handlerLog.clear();

            // when
            Account account = source(handlerOn(store, SnapshotPolicy.afterEvents(100)));

            // then
            assertThat(account.balance()).isEqualTo(100);
            assertThat(handlerLog.getEvents())
                .extracting(LogEvent::getMessage)
                .extracting(Message::getFormattedMessage)
                .anyMatch(message -> message.startsWith("Unsupported snapshot version"));
        }

        @Test
        void fallsBackToFullReconstructionAndLogsWarningOnIncompatiblePayload() {
            publish(
                new AccountCreated(ACCOUNT_ID, "Alice"),
                new FundsDeposited(ACCOUNT_ID, 100)
            );

            storeSnapshot("not-an-account", GlobalIndexPositions.of(2));

            handlerLog.clear();

            Account account = source();

            assertThat(account.balance()).isEqualTo(100);
            assertThat(handlerLog.getEvents())
                .extracting(LogEvent::getMessage)
                .extracting(Message::getFormattedMessage)
                .contains("Snapshot incompatible, falling back to full reconstruction for: Account#0.0.1 (" + ACCOUNT_ID + ")");
        }
    }

    @Nested
    class WhenSnapshotPolicyTriggers {

        @Test
        void snapshotStoredAfterEnoughEvents() {
            // SnapshotPolicy.afterEvents(2) triggers when eventsApplied >= 2
            publish(
                new AccountCreated(ACCOUNT_ID, "Alice"),
                new FundsDeposited(ACCOUNT_ID, 100),
                new FundsDeposited(ACCOUNT_ID, 200)
            );

            source();

            Snapshot snapshot = storedSnapshot();

            assertThat(snapshot).isNotNull();
            assertThat(snapshot.payload()).isEqualTo(new Account(ACCOUNT_ID, "Alice", 300));
        }

        @Test
        void snapshotStoredWhenEventCountEqualsThreshold() {
            // given
            publish(
                new AccountCreated(ACCOUNT_ID, "Alice"),
                new FundsDeposited(ACCOUNT_ID, 100)
            );

            // when
            source();

            // then
            Snapshot snapshot = snapshotStore.load(ACCOUNT_TYPE.qualifiedName(), ACCOUNT_ID, null).join();
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.payload()).isEqualTo(new Account(ACCOUNT_ID, "Alice", 100));
        }

        @Test
        void noSnapshotStoredWhenEventCountBelowThreshold() {
            // given
            publish(new AccountCreated(ACCOUNT_ID, "Alice"));

            // when
            source();

            // then
            assertThat(snapshotStore.load(ACCOUNT_TYPE.qualifiedName(), ACCOUNT_ID, null).join()).isNull();
        }

        @Test
        void snapshotStoredWhenMatchingEventSeen() {
            SnapshottingEntityLifecycleHandler<String, Account> matchHandler = handlerWithPolicy(
                SnapshotPolicy.whenEventMatches(msg -> msg.payload() instanceof AccountClosed)
            );

            publish(
                new AccountCreated(ACCOUNT_ID, "Alice"),
                new AccountClosed(ACCOUNT_ID)
            );

            source(matchHandler);

            assertThat(storedSnapshot()).isNotNull();
        }

        @Test
        void noSnapshotStoredWhenEntityComesEntirelyFromSnapshot() {
            publish(new AccountCreated(ACCOUNT_ID, "Alice"));
            storeSnapshot(new Account(ACCOUNT_ID, "Alice", 500), GlobalIndexPositions.of(1));

            source();  // no events after snapshot, so evolutionCount = 0

            // Verify entity comes from snapshot and no new write happened
            assertThat(evolveCalls.get()).isZero();
        }
    }

    @Nested
    class WhenSubscribed {

        @Test
        void liveEventUpdatesEntityState() {
            publish(new AccountCreated(ACCOUNT_ID, "Alice"), new FundsDeposited(ACCOUNT_ID, 100));

            ProcessingContext pc = new StubProcessingContext();
            Account initial = handler.source(ACCOUNT_ID, pc).join();
            AtomicReference<Account> stateRef = new AtomicReference<>(initial);

            handler.subscribe(managedEntity(stateRef), pc);
            eventStore.transaction(pc).appendEvent(
                new GenericEventMessage(
                    new MessageType(FundsDeposited.class),
                    new FundsDeposited(ACCOUNT_ID, 50)
                )
            );

            assertThat(stateRef.get().balance()).isEqualTo(150);
        }

        @Test
        void liveEventForAnotherEntityIsNotApplied() {
            publish(new AccountCreated(ACCOUNT_ID, "Alice"), new FundsDeposited(ACCOUNT_ID, 100));

            ProcessingContext pc = new StubProcessingContext();
            Account initial = handler.source(ACCOUNT_ID, pc).join();
            AtomicReference<Account> stateRef = new AtomicReference<>(initial);

            handler.subscribe(managedEntity(stateRef), pc);
            // A deposit tagged for a different account must not evolve this account.
            eventStore.transaction(pc).appendEvent(
                new GenericEventMessage(
                    new MessageType(FundsDeposited.class),
                    new FundsDeposited("account-2", 50)
                )
            );

            assertThat(stateRef.get().balance()).isEqualTo(100);
        }
    }

    private void publish(Object... events) {
        publish(eventStore, events);
    }

    private static void publish(EventStore store, Object... events) {
        store.publish(
            null,
            Arrays.stream(events)
                .map(e -> (EventMessage) new GenericEventMessage(new MessageType(e.getClass()), e))
                .toList()
        ).join();
    }

    private Account source() {
        return source(handler);
    }

    private @Nullable Snapshot storedSnapshot() {
        SnapshotMatch match = snapshotStore.load(
            List.of(new SnapshotCandidate(ACCOUNT_TYPE.qualifiedName(), Set.of(ACCOUNT_TYPE.version()))),
            ACCOUNT_ID,
            null
        ).orTimeout(1, TimeUnit.SECONDS).join();

        return match == null ? null : match.snapshot();
    }

    private Account source(SnapshottingEntityLifecycleHandler<String, Account> handler) {
        UnitOfWork uow = UnitOfWorkTestUtils.aUnitOfWork();

        return uow.executeWithResult(pc -> handler.source(ACCOUNT_ID, pc)).join();
    }

    private void storeSnapshot(Object payload, Position position) {
        storeSnapshot(payload, position, ACCOUNT_TYPE.version());
    }

    private void storeSnapshot(Object payload, Position position, String version) {
        snapshotStore.store(
            ACCOUNT_TYPE.qualifiedName(),
            ACCOUNT_ID,
            new Snapshot(position, version, payload, Instant.now(), Map.of()),
            null
        ).join();
    }

    private SnapshottingEntityLifecycleHandler<String, Account> handlerWithPolicy(SnapshotPolicy policy) {
        return handlerOn(eventStore, policy);
    }

    private SnapshottingEntityLifecycleHandler<String, Account> handlerOn(EventStore store, SnapshotPolicy policy) {
        return new SnapshottingEntityLifecycleHandler<>(
            store,
            (id, ctx) -> EventCriteria.havingTags(Tag.of("account", id)),
            new AnnotationBasedTagResolver(),
            new InitializingEntityEvolver<>(
                (id, msg, ctx) -> {
                    AccountCreated ac = (AccountCreated) msg.payload();
                    return new Account(ac.id(), ac.name(), 0);
                },
                (entity, event, ctx) -> event.payload() instanceof FundsDeposited fd
                    ? new Account(entity.id(), entity.name(), entity.balance() + fd.amount())
                    : entity
            ),
            policy,
            ACCOUNT_TYPE,
            CONVERTER,
            Account.class,
            snapshotStore
        );
    }

    private ManagedEntity<String, Account> managedEntity(AtomicReference<Account> stateRef) {
        return new ManagedEntity<>() {
            @Override
            public String identifier() {
                return ACCOUNT_ID;
            }

            @Override
            public Account entity() {
                return stateRef.get();
            }

            @Override
            public Account applyStateChange(UnaryOperator<@Nullable Account> change) {
                return stateRef.updateAndGet(change);
            }
        };
    }
}
