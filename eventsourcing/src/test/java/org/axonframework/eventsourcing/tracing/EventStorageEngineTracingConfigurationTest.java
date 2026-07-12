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

package org.axonframework.eventsourcing.tracing;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.eventsourcing.eventstore.GenericTaggedEventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class EventStorageEngineTracingConfigurationTest {

    private static final String APPEND_SPAN = "EventStorageEngine.appendTransaction";

    private final TestSpanFactory spanFactory = new TestSpanFactory();
    private AxonConfiguration configuration;

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @Test
    void tracesTheConfiguredStorageEngineUntilTheAppendTransactionRollsBack() {
        // given
        configuration = EventSourcingConfigurer.create()
                                              .componentRegistry(registry ->
                                                                         registry.registerComponent(
                                                                                 SpanFactory.class,
                                                                                 c -> spanFactory
                                                                         ))
                                              .start();
        EventStorageEngine storageEngine = configuration.getComponent(EventStorageEngine.class);

        // when
        AppendTransaction<?> transaction = storageEngine.appendEvents(
                AppendCondition.none(),
                null,
                List.of(new GenericTaggedEventMessage<>(EventTestUtils.createEvent(0), Set.of()))
        ).join();

        // then
        spanFactory.verifySpanActive(APPEND_SPAN);
        spanFactory.verifySpanHasType(APPEND_SPAN, TestSpanType.INTERNAL);

        // when
        transaction.rollback();

        // then
        spanFactory.verifySpanCompleted(APPEND_SPAN);
    }

    @Test
    void leavesTheStorageEngineUndecoratedWhenEventStoreTracingIsDisabled() {
        // given
        configuration = EventSourcingConfigurer.create()
                                              .componentRegistry(registry -> {
                                                  registry.registerComponent(SpanFactory.class, c -> spanFactory);
                                                  registry.registerComponent(
                                                          EventSourcingTracingSettings.class,
                                                          c -> new EventSourcingTracingSettings(
                                                                  false,
                                                                  true,
                                                                  EventSourcingTracingSettings.SpanAttributesProviders
                                                                          .enabledByDefault()
                                                          )
                                                  );
                                              })
                                              .start();
        EventStorageEngine storageEngine = configuration.getComponent(EventStorageEngine.class);

        // when
        AppendTransaction<?> transaction = storageEngine.appendEvents(
                AppendCondition.none(),
                null,
                List.of(new GenericTaggedEventMessage<>(EventTestUtils.createEvent(0), Set.of()))
        ).join();
        transaction.rollback();

        // then
        spanFactory.verifyNoSpan(APPEND_SPAN);
    }
}
