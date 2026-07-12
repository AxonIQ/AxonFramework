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

package org.axonframework.eventsourcing.handler.tracing;

import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.handler.EntityLifecycleHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.modelling.repository.ManagedEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TracingEntityLifecycleHandler}: the {@code source(...)} call must open an internal sourcing
 * span carrying the entity type and identifier; {@code initialize} and {@code subscribe} are pure pass-throughs and
 * must not open any span.
 */
class TracingEntityLifecycleHandlerTest {

    private TestSpanFactory spanFactory;
    private RecordingHandler delegate;
    private TracingEntityLifecycleHandler<String, Booking> testSubject;

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        delegate = new RecordingHandler();
        testSubject = new TracingEntityLifecycleHandler<>(delegate, spanFactory, "Booking");
    }

    @Test
    void opensASourcingSpanCarryingEntityTypeAndIdentifier() {
        // given
        ProcessingContext context = new StubProcessingContext();
        delegate.sourceResult = CompletableFuture.completedFuture(new Booking());

        // when
        testSubject.source("room-42", context).join();

        // then
        String spanName = "EntityLifecycleHandler.source Booking";
        spanFactory.verifySpanCompleted(spanName);
        spanFactory.verifySpanHasType(spanName, TestSpanType.INTERNAL);
        spanFactory.verifySpanHasAttributeValue(spanName, "axoniq.entity.type", "Booking");
        spanFactory.verifySpanHasAttributeValue(spanName, "axoniq.entity.id", "room-42");
    }

    @Test
    void initializeAndSubscribeAreUntracedPassThroughs() {
        // given
        ProcessingContext context = new StubProcessingContext();

        // when
        testSubject.initialize("room-42", context);

        // then
        assertThat(delegate.initialized).isEqualTo("room-42");
        // no sourcing span recorded for the pass-through call
        spanFactory.verifyNoSpan("EntityLifecycleHandler.source Booking");
    }

    @Test
    void describesItselfAsAWrapperOfTheDelegate() {
        // given
        RecordingComponentDescriptor descriptor = new RecordingComponentDescriptor();

        // when
        testSubject.describeTo(descriptor);

        // then
        assertThat(descriptor.wrapped).isSameAs(delegate);
    }

    static final class Booking {
    }

    private static final class RecordingHandler implements EntityLifecycleHandler<String, Booking> {

        private CompletableFuture<Booking> sourceResult = CompletableFuture.completedFuture(null);
        private String initialized;

        @Override
        public CompletableFuture<Booking> source(String identifier, ProcessingContext processingContext) {
            return sourceResult;
        }

        @Override
        public Booking initialize(String identifier, ProcessingContext context) {
            this.initialized = identifier;
            return new Booking();
        }

        @Override
        public void subscribe(ManagedEntity<String, Booking> entity, ProcessingContext context) {
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
        }
    }

    private static final class RecordingComponentDescriptor implements ComponentDescriptor {

        private Object wrapped;

        @Override
        public void describeWrapperOf(Object delegate) {
            this.wrapped = delegate;
        }

        @Override
        public void describeProperty(String name, Object object) {
        }

        @Override
        public void describeProperty(String name, java.util.Collection<?> collection) {
        }

        @Override
        public void describeProperty(String name, java.util.Map<?, ?> map) {
        }

        @Override
        public void describeProperty(String name, String value) {
        }

        @Override
        public void describeProperty(String name, Long value) {
        }

        @Override
        public void describeProperty(String name, Boolean value) {
        }
    }
}
