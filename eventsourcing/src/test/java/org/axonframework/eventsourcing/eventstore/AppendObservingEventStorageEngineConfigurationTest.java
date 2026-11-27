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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.infra.MockComponentDescriptor;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;

/**
 * Test class validating the {@link AppendObservingEventStorageEngine#decoratorDefinition() decorator definitions} of
 * the {@link AppendObservingEventStorageEngine}.
 *
 * @author Alexey Afanasyev
 */
class AppendObservingEventStorageEngineConfigurationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final List<AppendObservation> observations = new CopyOnWriteArrayList<>();
    private final AppendObserver recordingObserver = observations::add;

    @Nested
    class DecoratorDefinitionWithoutObserver {

        @Test
        void decoratesTheEventStorageEngine() {
            // given
            EventSourcingConfigurer configurer = EventSourcingConfigurer.create().componentRegistry(
                    registry -> registry.registerDecorator(AppendObservingEventStorageEngine.decoratorDefinition())
            );

            // when
            Configuration configuration = configurer.build();

            // then
            assertThat(configuration.getComponent(EventStorageEngine.class))
                    .isInstanceOf(AppendObservingEventStorageEngine.class);
        }

        @Test
        void reportsToTheRegisteredAppendObserverComponent() {
            // given
            EventSourcingConfigurer configurer = EventSourcingConfigurer.create().componentRegistry(
                    registry -> registry.registerComponent(AppendObserver.class, config -> recordingObserver)
                                        .registerDecorator(AppendObservingEventStorageEngine.decoratorDefinition())
            );
            EventStorageEngine engine = configurer.build().getComponent(EventStorageEngine.class);

            // when
            appendAndCommit(engine);

            // then
            assertThat(observations).singleElement()
                                    .extracting(AppendObservation::outcome)
                                    .isInstanceOf(AppendObservation.Outcome.Committed.class);
        }

        @Test
        void fallsBackToTheLoggingAppendObserverWhenNoObserverIsRegistered() {
            // given
            EventSourcingConfigurer configurer = EventSourcingConfigurer.create().componentRegistry(
                    registry -> registry.registerDecorator(AppendObservingEventStorageEngine.decoratorDefinition())
            );
            EventStorageEngine engine = configurer.build().getComponent(EventStorageEngine.class);
            MockComponentDescriptor descriptor = new MockComponentDescriptor();

            // when
            engine.describeTo(descriptor);

            // then
            assertThat((Object) descriptor.getProperty("observer")).isInstanceOf(LoggingAppendObserver.class);
        }
    }

    @Nested
    class DecoratorDefinitionWithObserver {

        @Test
        void reportsToTheGivenObserver() {
            // given
            EventSourcingConfigurer configurer = EventSourcingConfigurer.create().componentRegistry(
                    registry -> registry.registerDecorator(
                            AppendObservingEventStorageEngine.decoratorDefinition(recordingObserver)
                    )
            );
            EventStorageEngine engine = configurer.build().getComponent(EventStorageEngine.class);

            // when
            appendAndCommit(engine);

            // then
            assertThat(observations).hasSize(1);
        }
    }

    @Nested
    class DecorationOrder {

        @Test
        void observesAppendsOutsideOfTheSnapshotSupportingDecorator() {
            // given
            EventSourcingConfigurer configurer = EventSourcingConfigurer.create().componentRegistry(
                    registry -> registry.registerComponent(SnapshotStore.class, config -> new InMemorySnapshotStore(
                                                config.getComponent(GeneralConverter.class)
                                        ))
                                        .registerDecorator(AppendObservingEventStorageEngine.decoratorDefinition())
            );

            // when
            EventStorageEngine engine = configurer.build().getComponent(EventStorageEngine.class);

            // then
            assertThat(engine).isInstanceOfSatisfying(
                    AppendObservingEventStorageEngine.class,
                    observing -> assertThat(observing.decoratedDelegate())
                            .isInstanceOf(SnapshotCapableEventStorageEngine.class)
            );
        }
    }

    private static void appendAndCommit(EventStorageEngine engine) {
        AppendTransaction<?> transaction = awaitResult(engine.appendEvents(
                AppendCondition.none(),
                null,
                List.of(new GenericTaggedEventMessage<>(EventTestUtils.createEvent(0), Set.of()))
        ));
        Object commitResult = awaitResult(uncheckedCommit(transaction));
        awaitResult(uncheckedAfterCommit(transaction, commitResult));
    }

    /**
     * Waits for the given {@code future} to complete and returns its result, which must not be {@code null}. A failure
     * of the future is rethrown as is, without a {@link java.util.concurrent.CompletionException} wrapper.
     */
    private static <T> T awaitResult(CompletableFuture<T> future) {
        return Objects.requireNonNull(joinAndUnwrap(future, TIMEOUT), "The future completed with a null result.");
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Object> uncheckedCommit(AppendTransaction<?> transaction) {
        return ((AppendTransaction<Object>) transaction).commit();
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<ConsistencyMarker> uncheckedAfterCommit(AppendTransaction<?> transaction,
                                                                              Object commitResult) {
        return ((AppendTransaction<Object>) transaction).afterCommit(commitResult);
    }
}
