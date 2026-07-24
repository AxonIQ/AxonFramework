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

package org.axonframework.test.fixture;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingEventSinkTest {

    private final List<EventMessage> delegatePublished = new CopyOnWriteArrayList<>();
    private final EventSink delegate = new EventSink() {
        @Override
        public CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                               List<? extends EventMessage> events) {
            delegatePublished.addAll(events);
            return FutureUtils.emptyCompletedFuture();
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            // No state to describe.
        }
    };

    private final RecordingEventSink testSubject = new RecordingEventSink(delegate);

    private static EventMessage testEvent() {
        return new GenericEventMessage(new MessageType("test-event"), "payload");
    }

    private static UnitOfWork aUnitOfWork() {
        return new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create("test-unit-of-work");
    }

    @Nested
    class WithoutProcessingContext {

        @Test
        void recordsEventsImmediately() {
            // given
            EventMessage event = testEvent();

            // when
            testSubject.publish(null, List.of(event)).orTimeout(1, TimeUnit.SECONDS).join();

            // then
            assertThat(testSubject.recorded()).containsExactly(event);
            assertThat(delegatePublished).containsExactly(event);
        }
    }

    @Nested
    class WithProcessingContext {

        @Test
        void recordsEventsOnceTheContextCommits() {
            // given
            EventMessage event = testEvent();
            UnitOfWork unitOfWork = aUnitOfWork();

            // when - publishing during invocation defers recording until after commit
            unitOfWork.runOnInvocation(context -> {
                testSubject.publish(context, List.of(event)).orTimeout(1, TimeUnit.SECONDS).join();
                assertThat(testSubject.recorded()).isEmpty();
            });
            unitOfWork.execute().orTimeout(5, TimeUnit.SECONDS).join();

            // then
            assertThat(testSubject.recorded()).containsExactly(event);
        }

        @Test
        void doesNotRecordEventsWhenTheContextRollsBack() {
            // given
            EventMessage event = testEvent();
            UnitOfWork unitOfWork = aUnitOfWork();

            // when - the invocation phase fails after publishing
            unitOfWork.runOnInvocation(context -> {
                testSubject.publish(context, List.of(event)).orTimeout(1, TimeUnit.SECONDS).join();
                throw new IllegalStateException("simulating handler failure");
            });
            assertThatThrownBy(() -> unitOfWork.execute().orTimeout(5, TimeUnit.SECONDS).join())
                    .hasRootCauseInstanceOf(IllegalStateException.class);

            // then - the rolled-back event is never reported as recorded
            assertThat(testSubject.recorded()).isEmpty();
        }
    }

    @Test
    void resetClearsRecordedEvents() {
        // given
        testSubject.publish(null, List.of(testEvent())).orTimeout(1, TimeUnit.SECONDS).join();
        assertThat(testSubject.recorded()).isNotEmpty();

        // when
        testSubject.reset();

        // then
        assertThat(testSubject.recorded()).isEmpty();
    }
}
