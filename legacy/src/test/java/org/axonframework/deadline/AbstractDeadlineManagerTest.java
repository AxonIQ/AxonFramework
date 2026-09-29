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

import org.axonframework.messaging.core.CurrentScope;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.NoScopeDescriptor;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating {@link AbstractDeadlineManager}, in particular that:
 * <ul>
 *     <li>a call made without a bound {@link ProcessingContext} (field/constructor-injected usage) runs
 *     immediately;</li>
 *     <li>a call made through {@link AbstractDeadlineManager#withContext(ProcessingContext)} defers to that
 *     context's prepare-commit phase, and resolves an omitted scope through {@link CurrentScope} rather than
 *     {@link NoScopeDescriptor#INSTANCE}; and</li>
 *     <li>a dispatch interceptor's failure surfaces to the caller instead of being swallowed.</li>
 * </ul>
 */
class AbstractDeadlineManagerTest {

    private RecordingDeadlineManager testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new RecordingDeadlineManager();
    }

    @Nested
    class UnboundUsage {

        @Test
        void scheduleRunsImmediately() {
            testSubject.schedule(Instant.now(), "deadlineName", "payload", new StubScopeDescriptor("scope"));

            assertThat(testSubject.scheduled).hasSize(1);
        }

        @Test
        void scheduleWithoutExplicitScopeFallsBackToNoScopeDescriptor() {
            testSubject.schedule(Instant.now(), "deadlineName", "payload");

            assertThat(testSubject.scheduled).singleElement()
                                             .extracting(RecordingDeadlineManager.ScheduledCall::scope)
                                             .isSameAs(NoScopeDescriptor.INSTANCE);
        }

        @Test
        void cancelScheduleRunsImmediately() {
            testSubject.cancelSchedule("deadlineName", "scheduleId");

            assertThat(testSubject.cancelledSchedules).containsExactly("deadlineName/scheduleId");
        }

        @Test
        void cancelAllRunsImmediately() {
            testSubject.cancelAll("deadlineName");

            assertThat(testSubject.cancelledAll).containsExactly("deadlineName");
        }
    }

    @Nested
    class ContextBoundUsage {

        @Test
        void scheduleIsDeferredUntilThePrepareCommitPhase() {
            StubProcessingContext context = new StubProcessingContext();
            DeadlineManager bound = testSubject.withContext(context);

            bound.schedule(Instant.now(), "deadlineName", "payload", new StubScopeDescriptor("scope"));
            assertThat(testSubject.scheduled).isEmpty();

            context.moveToPhase(ProcessingLifecycle.DefaultPhases.PREPARE_COMMIT);
            assertThat(testSubject.scheduled).hasSize(1);
        }

        @Test
        void scheduleWithoutExplicitScopeResolvesTheScopeRegisteredOnTheContext() {
            ScopeDescriptor registeredScope = new StubScopeDescriptor("registered");
            StubProcessingContext context = new StubProcessingContext();
            context.putResource(CurrentScope.RESOURCE_KEY, registeredScope);
            DeadlineManager bound = testSubject.withContext(context);

            bound.schedule(Instant.now(), "deadlineName", "payload");
            context.moveToPhase(ProcessingLifecycle.DefaultPhases.PREPARE_COMMIT);

            assertThat(testSubject.scheduled).singleElement()
                                             .extracting(RecordingDeadlineManager.ScheduledCall::scope)
                                             .isSameAs(registeredScope);
        }

        @Test
        void cancelScheduleIsDeferredUntilThePrepareCommitPhase() {
            StubProcessingContext context = new StubProcessingContext();
            DeadlineManager bound = testSubject.withContext(context);

            bound.cancelSchedule("deadlineName", "scheduleId");
            assertThat(testSubject.cancelledSchedules).isEmpty();

            context.moveToPhase(ProcessingLifecycle.DefaultPhases.PREPARE_COMMIT);
            assertThat(testSubject.cancelledSchedules).containsExactly("deadlineName/scheduleId");
        }
    }

    @Nested
    class DispatchInterceptors {

        @Test
        void aFailingDispatchInterceptorSurfacesItsExceptionRatherThanBeingSwallowed() {
            testSubject.registerDispatchInterceptor((message, context, chain) -> {
                throw new IllegalStateException("interceptor failure");
            });

            assertThatThrownBy(
                    () -> testSubject.schedule(Instant.now(), "deadlineName", "payload", new StubScopeDescriptor("scope"))
            ).isInstanceOf(IllegalStateException.class).hasMessage("interceptor failure");
            assertThat(testSubject.scheduled).isEmpty();
        }

        @Test
        void aDispatchInterceptorSwallowingTheMessageFailsTheScheduleCall() {
            // given
            testSubject.registerDispatchInterceptor((message, context, chain) -> MessageStream.empty());

            // when / then
            assertThatThrownBy(
                    () -> testSubject.schedule(Instant.now(), "deadlineName", "payload", new StubScopeDescriptor("scope"))
            ).isInstanceOf(IllegalStateException.class).hasMessageContaining("deadlineName");
            assertThat(testSubject.scheduled).isEmpty();
        }

        @Test
        void aDispatchInterceptorSwallowingTheMessageFailsTheContextBoundScheduleCall() {
            // given
            testSubject.registerDispatchInterceptor((message, context, chain) -> MessageStream.empty());
            StubProcessingContext context = new StubProcessingContext();
            DeadlineManager bound = testSubject.withContext(context);

            // when / then
            assertThatThrownBy(
                    () -> bound.schedule(Instant.now(), "deadlineName", "payload", new StubScopeDescriptor("scope"))
            ).isInstanceOf(IllegalStateException.class).hasMessageContaining("deadlineName");
            context.moveToPhase(ProcessingLifecycle.DefaultPhases.PREPARE_COMMIT);
            assertThat(testSubject.scheduled).isEmpty();
        }
    }

    private record StubScopeDescriptor(String description) implements ScopeDescriptor {

        @Override
        public String scopeDescription() {
            return description;
        }
    }

    /**
     * A minimal, test-only {@link AbstractDeadlineManager} subclass: records every call it receives instead of
     * actually scheduling/cancelling anything against a real backend.
     */
    private static final class RecordingDeadlineManager extends AbstractDeadlineManager {

        private final List<ScheduledCall> scheduled = new ArrayList<>();
        private final List<String> cancelledSchedules = new ArrayList<>();
        private final List<String> cancelledAll = new ArrayList<>();
        private final AtomicInteger idGenerator = new AtomicInteger();

        @Override
        protected String doSchedule(DeadlineMessage deadlineMessage,
                                    ScopeDescriptor scope,
                                    @Nullable ProcessingContext context) {
            String scheduleId = "schedule-" + idGenerator.incrementAndGet();
            runOnPrepareCommitOrNow(context, () -> scheduled.add(new ScheduledCall(scheduleId, deadlineMessage, scope)));
            return scheduleId;
        }

        @Override
        protected void doCancelSchedule(String deadlineName, String scheduleId, @Nullable ProcessingContext context) {
            runOnPrepareCommitOrNow(context, () -> cancelledSchedules.add(deadlineName + "/" + scheduleId));
        }

        @Override
        protected void doCancelAll(String deadlineName, @Nullable ProcessingContext context) {
            runOnPrepareCommitOrNow(context, () -> cancelledAll.add(deadlineName));
        }

        @Override
        protected void doCancelAllWithinScope(String deadlineName,
                                              ScopeDescriptor scope,
                                              @Nullable ProcessingContext context) {
            runOnPrepareCommitOrNow(context, () -> cancelledAll.add(deadlineName + "@" + scope.scopeDescription()));
        }

        private record ScheduledCall(String scheduleId, DeadlineMessage message, ScopeDescriptor scope) {
        }
    }
}
