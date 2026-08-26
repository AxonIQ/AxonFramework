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

package org.axonframework.modelling.saga;

import org.jspecify.annotations.NonNull;
import jakarta.inject.Inject;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.common.util.MockException;
import org.junit.jupiter.api.*;

import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link SimpleResourceInjector}.
 *
 * @author Allard Buijze
 */
class SimpleResourceInjectorTest {

    private SimpleResourceInjector testSubject;

    @Test
    void injectFieldResource() {
        SomeFieldResource expectedFieldResource = new SomeFieldResource();
        testSubject = new SimpleResourceInjector(expectedFieldResource);
        final StubSaga saga = new StubSaga();
        testSubject.injectResources(saga);

        assertNull(saga.getSomeWeirdResource());
        assertSame(expectedFieldResource, saga.getSomeFieldResource());
    }

    @Test
    void injectMethodResource() {
        final SomeMethodResource expectedMethodResource = new SomeMethodResource();
        testSubject = new SimpleResourceInjector(expectedMethodResource);
        final StubSaga saga = new StubSaga();
        testSubject.injectResources(saga);

        assertNull(saga.getSomeWeirdResource());
        assertSame(expectedMethodResource, saga.getSomeMethodResource());
    }

    @Test
    void injectFieldAndMethodResources() {
        final SomeFieldResource expectedFieldResource = new SomeFieldResource();
        final SomeMethodResource expectedMethodResource = new SomeMethodResource();
        testSubject = new SimpleResourceInjector(expectedFieldResource, expectedMethodResource);
        final StubSaga saga = new StubSaga();
        testSubject.injectResources(saga);

        assertNull(saga.getSomeWeirdResource());
        assertSame(expectedFieldResource, saga.getSomeFieldResource());
        assertSame(expectedMethodResource, saga.getSomeMethodResource());
    }

    @Test
    void injectResource_ExceptionsIgnored() {
        final SomeMethodResource resource = new SomeMethodResource();
        testSubject = new SimpleResourceInjector(resource, new SomeWeirdResource());
        final StubSaga saga = new StubSaga();
        testSubject.injectResources(saga);

        assertNull(saga.getSomeWeirdResource());
        assertSame(resource, saga.getSomeMethodResource());
    }

    private static class StubSaga implements Saga<StubSaga> {

        @Inject
        private SomeFieldResource someFieldResource;
        private SomeMethodResource someMethodResource;
        private SomeWeirdResource someWeirdResource;

        @Override
        public String getSagaIdentifier() {
            return "id";
        }

        @Override
        public AssociationValues getAssociationValues() {
            return new AssociationValuesImpl();
        }

        @Override
        public <R> R invoke(Function<StubSaga, R> invocation) {
            return invocation.apply(this);
        }

        @Override
        public void execute(Consumer<StubSaga> invocation) {
            invocation.accept(this);
        }

        @Override
        public boolean canHandle(@NonNull EventMessage event, @NonNull ProcessingContext context) {
            return true;
        }

        @Override
        public Object handleSync(@NonNull EventMessage event, @NonNull ProcessingContext context) {
            return null;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        public SomeFieldResource getSomeFieldResource() {
            return someFieldResource;
        }

        public SomeMethodResource getSomeMethodResource() {
            return someMethodResource;
        }

        @Inject
        public void setSomeMethodResource(SomeMethodResource someMethodResource) {
            this.someMethodResource = someMethodResource;
        }

        public SomeWeirdResource getSomeWeirdResource() {
            return someWeirdResource;
        }

        public void setSomeWeirdResource(SomeWeirdResource someWeirdResource) {
            throw new MockException();
        }
    }

    private static class SomeFieldResource {

    }

    private static class SomeMethodResource {

    }

    private static class SomeWeirdResource {

    }
}
