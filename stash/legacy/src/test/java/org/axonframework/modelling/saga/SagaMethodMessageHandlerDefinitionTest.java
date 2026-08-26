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

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.modelling.saga.metamodel.AnnotationSagaMetaModelFactory;
import org.axonframework.modelling.saga.metamodel.SagaModel;
import org.junit.jupiter.api.*;

import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;
import static org.axonframework.modelling.util.ConcurrencyUtils.testConcurrent;
import static org.junit.jupiter.api.Assertions.*;

class SagaMethodMessageHandlerDefinitionTest {

    private SagaMethodMessageHandlerDefinition testSubject;
    private SagaModel<StubAnnotatedSaga> sagaModel;
    private static final String TEST_PROPERTY_NAME = "testProperty";
    private static final int TEST_PROPERTY_VALUE = 42;

    @BeforeEach
    void setup() {
        testSubject = new SagaMethodMessageHandlerDefinition();
        sagaModel = new AnnotationSagaMetaModelFactory().modelOf(StubAnnotatedSaga.class);
    }

    @Test
    void shouldWrapHandler() {
        EventMessage eventMessage = asEventMessage(new TestEvent(TEST_PROPERTY_VALUE));
        testWrapHandler(eventMessage);
    }

    @Test
    void shouldWrapHandlerConcurrently() {
        EventMessage eventMessage = asEventMessage(new TestEvent(TEST_PROPERTY_VALUE));
        testConcurrent(4, () -> testWrapHandler(eventMessage));
    }

    public void testWrapHandler(EventMessage eventMessage) {
        MessageHandlingMember<?> result = testSubject.wrapHandler(sagaModel.findHandlerMethods(eventMessage,
                                                                                               StubProcessingContext.forMessage(eventMessage)).get(0));
        assertNotNull(result);
        assertTrue(result instanceof SagaMethodMessageHandlingMember);
        assertEquals(SagaCreationPolicy.NONE, ((SagaMethodMessageHandlingMember<?>) result).getCreationPolicy());
    }

    @SagaEventHandler(associationProperty = TEST_PROPERTY_NAME)
    public void on(TestEvent event) {
    }

    private class TestEvent {

        private final int testProperty;

        public TestEvent(int testProperty) {
            this.testProperty = testProperty;
        }

        public int getTestProperty() {
            return testProperty;
        }
    }

    @SuppressWarnings("unused")
    private static class StubAnnotatedSaga {

        @SagaEventHandler(associationProperty = TEST_PROPERTY_NAME, associationResolver = TestAssociationResolver.class)
        public void handleTestEvent(TestEvent event) {
        }
    }

    /**
     * Sleep added to constructor such that the
     * {@link SagaMethodMessageHandlerDefinitionTest#shouldWrapHandlerConcurrently()} test would always fail with a non
     * thread safe map.
     */
    private static class TestAssociationResolver extends PayloadAssociationResolver {

        public TestAssociationResolver() {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
