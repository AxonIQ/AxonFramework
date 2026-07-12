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

package org.axonframework.modelling.repository.tracing;

import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class TracingRepositoryTest {

    private TestSpanFactory spanFactory;
    private RecordingRepository delegate;
    private TracingRepository<String, Booking> testSubject;

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        delegate = new RecordingRepository();
        testSubject = new TracingRepository<>(delegate, spanFactory);
    }

    @Nested
    class Load {

        @Test
        void opensAnInternalSpanWithEntityTypeAndIdentifier() {
            // given
            ProcessingContext context = new StubProcessingContext();
            delegate.loadResult = CompletableFuture.completedFuture(null);

            // when
            testSubject.load("room-42", context).join();

            // then
            spanFactory.verifySpanCompleted("Repository.load Booking");
            spanFactory.verifySpanHasType("Repository.load Booking", TestSpanType.INTERNAL);
            spanFactory.verifySpanHasAttributeValue("Repository.load Booking", "axoniq.entity.type", "Booking");
            spanFactory.verifySpanHasAttributeValue("Repository.load Booking", "axoniq.entity.id", "room-42");
        }
    }

    @Nested
    class Persist {

        @Test
        void opensAnInternalSpanForPersist() {
            // given
            ProcessingContext context = new StubProcessingContext();

            // when
            testSubject.persist("room-42", new Booking(), context);

            // then
            spanFactory.verifySpanCompleted("Repository.persist Booking");
            spanFactory.verifySpanHasType("Repository.persist Booking", TestSpanType.INTERNAL);
        }
    }

    @Nested
    class ContextBranching {

        // A downstream span (e.g. SnapshotStore.load, or the event-sourcing source) created by the delegate must
        // nest under the repository lifecycle span, not under the repository's own ancestor. This is achieved by
        // branching the ProcessingContext with the lifecycle span's scope before handing it to the delegate.

        @Test
        void loadBranchesContextSoDownstreamSpansNestUnderIt() {
            // given
            ProcessingContext context = new StubProcessingContext();
            delegate.loadResult = CompletableFuture.completedFuture(null);
            delegate.onInvocation = received -> spanFactory.createInternalSpan("SnapshotStore.load", received)
                                                           .start()
                                                           .close();

            // when
            testSubject.load("room-42", context).join();

            // then
            spanFactory.verifySpanHasParent("SnapshotStore.load", "Repository.load Booking");
        }

        @Test
        void loadOrCreateBranchesContextSoDownstreamSpansNestUnderIt() {
            // given
            ProcessingContext context = new StubProcessingContext();
            delegate.loadResult = CompletableFuture.completedFuture(null);
            delegate.onInvocation = received -> spanFactory.createInternalSpan("SnapshotStore.load", received)
                                                           .start()
                                                           .close();

            // when
            testSubject.loadOrCreate("room-42", context).join();

            // then
            spanFactory.verifySpanHasParent("SnapshotStore.load", "Repository.loadOrCreate Booking");
        }

        @Test
        void persistBranchesContextSoDownstreamSpansNestUnderIt() {
            // given
            ProcessingContext context = new StubProcessingContext();
            delegate.onInvocation = received -> spanFactory.createInternalSpan("downstream", received)
                                                           .start()
                                                           .close();

            // when
            testSubject.persist("room-42", new Booking(), context);

            // then
            spanFactory.verifySpanHasParent("downstream", "Repository.persist Booking");
        }

        @Test
        void attachBranchesContextSoDownstreamSpansNestUnderIt() {
            // given
            ProcessingContext context = new StubProcessingContext();
            delegate.onInvocation = received -> spanFactory.createInternalSpan("downstream", received)
                                                           .start()
                                                           .close();

            // when
            testSubject.attach(new StubManagedEntity(), context);

            // then
            spanFactory.verifySpanHasParent("downstream", "Repository.attach Booking");
        }
    }

    @Nested
    class Introspection {

        @Test
        void describesItselfAsAWrapperOfTheDelegate() {
            // given
            RecordingComponentDescriptor descriptor = new RecordingComponentDescriptor();

            // when
            testSubject.describeTo(descriptor);

            // then
            assertThat(descriptor.wrapped).isSameAs(delegate);
        }
    }

    /**
     * Minimal repository stub recording inputs.
     */
    private static final class RecordingRepository implements Repository.LifecycleManagement<String, Booking> {

        private CompletableFuture<ManagedEntity<String, Booking>> loadResult = CompletableFuture.completedFuture(null);

        /**
         * Invoked with the {@link ProcessingContext} the decorator actually hands the delegate, so a test can create a
         * downstream span against it and assert the parent the tracing decorator established.
         */
        private Consumer<ProcessingContext> onInvocation = context -> {
        };

        @Override
        public Class<Booking> entityType() {
            return Booking.class;
        }

        @Override
        public Class<String> idType() {
            return String.class;
        }

        @Override
        public CompletableFuture<ManagedEntity<String, Booking>> load(String identifier,
                                                                      ProcessingContext processingContext) {
            onInvocation.accept(processingContext);
            return loadResult;
        }

        @Override
        public CompletableFuture<ManagedEntity<String, Booking>> loadOrCreate(String identifier,
                                                                              ProcessingContext processingContext) {
            onInvocation.accept(processingContext);
            return loadResult;
        }

        @Override
        public ManagedEntity<String, Booking> persist(String identifier,
                                                      Booking entity,
                                                      ProcessingContext processingContext) {
            onInvocation.accept(processingContext);
            return null;
        }

        @Override
        public ManagedEntity<String, Booking> attach(ManagedEntity<String, Booking> entity,
                                                     ProcessingContext processingContext) {
            onInvocation.accept(processingContext);
            return entity;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
        }
    }

    static final class Booking {

    }

    /**
     * Minimal {@link ManagedEntity} stub for exercising {@link TracingRepository#attach}.
     */
    private static final class StubManagedEntity implements ManagedEntity<String, Booking> {

        private Booking entity = new Booking();

        @Override
        public String identifier() {
            return "room-42";
        }

        @Override
        public Booking entity() {
            return entity;
        }

        @Override
        public Booking applyStateChange(java.util.function.UnaryOperator<Booking> change) {
            entity = change.apply(entity);
            return entity;
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
