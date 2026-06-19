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

package org.axonframework.messaging.eventhandling.processing;

import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link ProcessorEventHandlingComponents} event handling behaviour.
 *
 * @author Jakob Hatzl
 * @since 5.1.2
 */
class ProcessorEventHandlingComponentsTest {

    @Nested
    class WhenHandlingBatch {

        @Test
        void aggregateResourcesFromEntryAreAvailableInPerEventContext() {
            // given
            EventMessage event = EventTestUtils.createEvent(0);
            AtomicReference<ProcessingContext> capturedContext = new AtomicReference<>();

            SimpleEventHandlingComponent component = SimpleEventHandlingComponent.create("test");
            component.subscribe(event.type().qualifiedName(), (e, c) -> {
                capturedContext.set(c);
                return MessageStream.empty();
            });

            ProcessorEventHandlingComponents testSubject =
                    new ProcessorEventHandlingComponents(List.of(component));

            Context entryContext = Context.with(LegacyResources.AGGREGATE_IDENTIFIER_KEY, "agg-123")
                                          .withResource(LegacyResources.AGGREGATE_SEQUENCE_NUMBER_KEY, 7L)
                                          .withResource(LegacyResources.AGGREGATE_TYPE_KEY, "MyAggregate");
            MessageStream.Entry<EventMessage> entry = new SimpleEntry<>(event, entryContext);

            // when
            UnitOfWorkTestUtils.aUnitOfWork()
                               .executeWithResult(ctx -> testSubject.handle(List.of(entry), ctx)
                                                                    .asCompletableFuture())
                               .orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                               .join();

            // then
            assertThat(capturedContext.get()).isNotNull();
            assertThat(capturedContext.get().getResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY))
                    .isEqualTo("agg-123");
            assertThat(capturedContext.get().getResource(LegacyResources.AGGREGATE_SEQUENCE_NUMBER_KEY))
                    .isEqualTo(7L);
            assertThat(capturedContext.get().getResource(LegacyResources.AGGREGATE_TYPE_KEY))
                    .isEqualTo("MyAggregate");
        }

        @Test
        void trackingTokenFromEntryIsAvailableInPerEventContext() {
            // given
            EventMessage event = EventTestUtils.createEvent(0);
            AtomicReference<ProcessingContext> capturedContext = new AtomicReference<>();

            SimpleEventHandlingComponent component = SimpleEventHandlingComponent.create("test");
            component.subscribe(event.type().qualifiedName(), (e, c) -> {
                capturedContext.set(c);
                return MessageStream.empty();
            });

            ProcessorEventHandlingComponents testSubject =
                    new ProcessorEventHandlingComponents(List.of(component));

            TrackingToken perEventToken = new GlobalSequenceTrackingToken(42L);
            Context entryContext = Context.with(TrackingToken.RESOURCE_KEY, perEventToken);
            MessageStream.Entry<EventMessage> entry = new SimpleEntry<>(event, entryContext);

            // when
            UnitOfWorkTestUtils.aUnitOfWork()
                               .executeWithResult(ctx -> testSubject.handle(List.of(entry), ctx)
                                                                    .asCompletableFuture())
                               .orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                               .join();

            // then
            assertThat(capturedContext.get()).isNotNull();
            assertThat(capturedContext.get().getResource(TrackingToken.RESOURCE_KEY))
                    .isEqualTo(perEventToken);
        }

        @Test
        void batchContextResourcesAreAvailableWhenEntryHasNoResources() {
            // given
            EventMessage event = EventTestUtils.createEvent(0);
            Context.ResourceKey<String> batchKey = Context.ResourceKey.withLabel("batchResource");
            AtomicReference<ProcessingContext> capturedContext = new AtomicReference<>();

            SimpleEventHandlingComponent component = SimpleEventHandlingComponent.create("test");
            component.subscribe(event.type().qualifiedName(), (e, c) -> {
                capturedContext.set(c);
                return MessageStream.empty();
            });

            ProcessorEventHandlingComponents testSubject =
                    new ProcessorEventHandlingComponents(List.of(component));

            // entry with empty context (subscribing processor pattern)
            MessageStream.Entry<EventMessage> entry = new SimpleEntry<>(event, Context.empty());

            // when
            UnitOfWorkTestUtils.aUnitOfWork()
                               .executeWithResult(ctx -> {
                                   ProcessingContext batchContext = ctx.withResource(batchKey, "batch-value");
                                   return testSubject.handle(List.of(entry), batchContext)
                                                     .asCompletableFuture();
                               })
                               .orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                               .join();

            // then - batch context resources are preserved when entry context is empty
            assertThat(capturedContext.get()).isNotNull();
            assertThat(capturedContext.get().getResource(batchKey)).isEqualTo("batch-value");
        }

        @Test
        void batchContextResourcesAreMergedWithEntryResources() {
            // given
            EventMessage event = EventTestUtils.createEvent(0);
            Context.ResourceKey<String> batchKey = Context.ResourceKey.withLabel("batchResource");
            AtomicReference<ProcessingContext> capturedContext = new AtomicReference<>();

            SimpleEventHandlingComponent component = SimpleEventHandlingComponent.create("test");
            component.subscribe(event.type().qualifiedName(), (e, c) -> {
                capturedContext.set(c);
                return MessageStream.empty();
            });

            ProcessorEventHandlingComponents testSubject =
                    new ProcessorEventHandlingComponents(List.of(component));

            TrackingToken perEventToken = new GlobalSequenceTrackingToken(42L);
            Context entryContext = Context.with(TrackingToken.RESOURCE_KEY, perEventToken);
            MessageStream.Entry<EventMessage> entry = new SimpleEntry<>(event, entryContext);

            // when
            UnitOfWorkTestUtils.aUnitOfWork()
                               .executeWithResult(ctx -> {
                                   ProcessingContext batchContext = ctx.withResource(batchKey, "batch-value");
                                   return testSubject.handle(List.of(entry), batchContext)
                                                     .asCompletableFuture();
                               })
                               .orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                               .join();

            // then - batch context resources are preserved when entry context is empty
            assertThat(capturedContext.get()).isNotNull();
            assertThat(capturedContext.get().getResource(batchKey)).isEqualTo("batch-value");
            assertThat(capturedContext.get().getResource(TrackingToken.RESOURCE_KEY))
                    .isEqualTo(perEventToken);
        }
    }
}
