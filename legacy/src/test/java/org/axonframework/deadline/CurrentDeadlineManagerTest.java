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

package org.axonframework.deadline;

import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating {@link CurrentDeadlineManager}: its {@link Context.ResourceKey} round-trips a
 * {@link DeadlineManager} through a {@link ProcessingContext} unchanged, and {@link CurrentDeadlineManager#forContext}
 * resolves it or fails loudly when nothing is registered.
 */
class CurrentDeadlineManagerTest {

    @Nested
    class ResourceKey {

        @Test
        void aDeadlineManagerRegisteredUnderTheResourceKeyRoundTripsThroughTheContext() {
            DeadlineManager deadlineManager = stubDeadlineManager();
            ProcessingContext context = new StubProcessingContext().withResource(
                    CurrentDeadlineManager.RESOURCE_KEY, deadlineManager
            );

            assertThat(context.getResource(CurrentDeadlineManager.RESOURCE_KEY)).isSameAs(deadlineManager);
        }

        @Test
        void nothingIsRegisteredByDefault() {
            ProcessingContext context = new StubProcessingContext();

            assertThat(context.getResource(CurrentDeadlineManager.RESOURCE_KEY)).isNull();
        }
    }

    @Nested
    class ForContext {

        @Test
        void returnsTheDeadlineManagerRegisteredForTheContext() {
            DeadlineManager deadlineManager = stubDeadlineManager();
            ProcessingContext context = new StubProcessingContext().withResource(
                    CurrentDeadlineManager.RESOURCE_KEY, deadlineManager
            );

            assertThat(CurrentDeadlineManager.forContext(context)).isSameAs(deadlineManager);
        }

        @Test
        void throwsWhenNoDeadlineManagerIsRegisteredForTheContext() {
            ProcessingContext context = new StubProcessingContext();

            assertThatThrownBy(() -> CurrentDeadlineManager.forContext(context))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void throwsWhenContextIsNull() {
            assertThatThrownBy(() -> CurrentDeadlineManager.forContext(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    private static DeadlineManager stubDeadlineManager() {
        return new DeadlineManager() {
            @Override
            public String schedule(Instant triggerDateTime, String deadlineName, Object messageOrPayload,
                                   ScopeDescriptor deadlineScope) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public void cancelSchedule(String deadlineName, String scheduleId) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public void cancelAll(String deadlineName) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public void cancelAllWithinScope(String deadlineName, ScopeDescriptor scope) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
    }
}
