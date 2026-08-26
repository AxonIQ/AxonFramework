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

package org.axonframework.modelling.saga.metamodel;

import org.axonframework.messaging.LegacyHandlerInvoker;
import org.axonframework.common.AxonException;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.interception.annotation.NoMoreInterceptors;
import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Optional;

import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;
import static org.junit.jupiter.api.Assertions.*;

class AnnotationSagaMetaModelFactoryTest {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private AnnotationSagaMetaModelFactory testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new AnnotationSagaMetaModelFactory();
    }

    @Test
    void inspectSaga() {
        SagaModel<MySaga> sagaModel = testSubject.modelOf(MySaga.class);

        EventMessage event = asEventMessage(new MySagaStartEvent("value"));
        Optional<AssociationValue> actual = sagaModel.resolveAssociation(event, StubProcessingContext.forMessage(event));
        assertTrue(actual.isPresent());
        assertEquals("value", actual.get().getValue());
        assertEquals("property", actual.get().getKey());
    }

    @Test
    void chainedInterceptorShouldDefaultToNoMoreInterceptors() {
        SagaModel<MySaga> sagaModel = testSubject.modelOf(MySaga.class);

        MySaga saga = new MySaga();
        EventMessage event = asEventMessage(new MySagaStartEvent("foo"));
        Optional<MessageHandlingMember<? super MySaga>> handler = sagaModel
                .findHandlerMethods(event, StubProcessingContext.forMessage(event)).stream().findFirst();
        assertTrue(handler.isPresent());
        MessageHandlerInterceptorMemberChain<MySaga> interceptorChain = testSubject.chainedInterceptor(MySaga.class);
        assertThrows(FooException.class, () -> LegacyHandlerInvoker.handleSync(interceptorChain, event, saga, handler.get()));
    }

    @Test
    @Disabled("TODO #3062 - Exception Handler support")
    void exceptionShouldBeCaughtByExceptionHandler() throws Exception {
        SagaModel<MySagaWithErrorHandler> sagaModel = testSubject.modelOf(MySagaWithErrorHandler.class);

        MySagaWithErrorHandler saga = new MySagaWithErrorHandler();
        EventMessage event = asEventMessage(new MySagaStartEvent("foo"));
        Optional<MessageHandlingMember<? super MySagaWithErrorHandler>> handler = sagaModel
                .findHandlerMethods(event, StubProcessingContext.forMessage(event)).stream().findFirst();
        assertTrue(handler.isPresent());
        MessageHandlerInterceptorMemberChain<MySagaWithErrorHandler> interceptorChain = testSubject.chainedInterceptor(
                MySagaWithErrorHandler.class);
        Object result = LegacyHandlerInvoker.handleSync(interceptorChain, event, saga, handler.get());
        assertNull(result);
    }

    @Test
    void messageHandlerInterceptorShouldDefaultToNoMoreInterceptors() {
        assertEquals(NoMoreInterceptors.class, testSubject.chainedInterceptor(MySaga.class).getClass());
    }

    public static class MySaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "property")
        public void handle(MySagaStartEvent event) {
            if ("foo".equals(event.getProperty())) {
                throw new FooException("value was foo");
            }
        }

        @SagaEventHandler(associationProperty = "property")
        public void handle(MySagaUpdateEvent event) {

        }

        @SagaEventHandler(associationProperty = "property")
        public void handle(MySagaEndEvent event) {

        }
    }

    public static class MySagaWithErrorHandler extends MySaga {

        @ExceptionHandler
        public void on(Exception e) {
            logger.info("caught", e);
        }
    }

    public abstract static class MySagaEvent {

        private final String property;

        public MySagaEvent(String property) {
            this.property = property;
        }

        public String getProperty() {
            return property;
        }
    }

    private static class MySagaStartEvent extends MySagaEvent {

        public MySagaStartEvent(String property) {
            super(property);
        }
    }

    private static class MySagaUpdateEvent extends MySagaEvent {

        public MySagaUpdateEvent(String property) {
            super(property);
        }
    }

    private static class MySagaEndEvent extends MySagaEvent {

        public MySagaEndEvent(String property) {
            super(property);
        }
    }

    private static class FooException extends AxonException {

        public FooException(String message) {
            super(message);
        }
    }
}
