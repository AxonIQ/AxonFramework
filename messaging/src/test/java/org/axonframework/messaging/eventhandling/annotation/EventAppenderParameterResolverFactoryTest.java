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

package org.axonframework.messaging.eventhandling.annotation;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventhandling.gateway.ProcessingContextEventAppender;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link EventAppenderParameterResolverFactory}.
 *
 * @author Mitchell Herrijgers
 */
class EventAppenderParameterResolverFactoryTest {

    private final Configuration configuration = mock(Configuration.class);
    private final EventSink eventSink = mock(EventSink.class);
    private final MessageTypeResolver messageTypeResolver = mock(MessageTypeResolver.class);

    private final EventAppenderParameterResolverFactory testSubject = new EventAppenderParameterResolverFactory();

    @BeforeEach
    void setUp() {
        when(configuration.getComponent(EventSink.class)).thenReturn(eventSink);
        when(configuration.getComponent(MessageTypeResolver.class)).thenReturn(messageTypeResolver);
    }

    @Test
    void injectsEventAppenderBasedOnProcessingContext() throws Exception {
        ProcessingContext processingContext = StubProcessingContext.withComponents(registry -> {
            registry.registerComponent(EventSink.class, c -> eventSink);
            registry.registerComponent(MessageTypeResolver.class, c -> messageTypeResolver);
        });

        Method method = getClass().getMethod("methodWithEventAppenderParameter", EventAppender.class);
        ParameterResolver<?> instance = testSubject.createInstance(method, method.getParameters(), 0);
        assertNotNull(instance);
        Object injectedParameter = instance.resolveParameterValue(processingContext).join();
        assertInstanceOf(ProcessingContextEventAppender.class, injectedParameter);
    }

    @Test
    void doesNotInjectIntoGenericParameter() throws Exception {
        Method method = getClass().getMethod("methodWithOtherParameter", Object.class);
        ParameterResolver<?> instance = testSubject.createInstance(method, method.getParameters(), 0);
        assertNull(instance);
    }

    @Test
    void doesNotInjectIntoASubtypeOfEventAppender() throws Exception {
        Method method = getClass().getMethod("methodWithEventAppenderSubtypeParameter", CustomEventAppender.class);
        ParameterResolver<?> instance = testSubject.createInstance(method, method.getParameters(), 0);
        assertNull(instance);
    }

    public void methodWithEventAppenderParameter(
            EventAppender eventAppender
    ) {
        // This method is used to test the EventAppenderParameterResolverFactory
    }

    public void methodWithOtherParameter(
            Object otherParameter
    ) {
        // This method is used to test the EventAppenderParameterResolverFactory
    }

    public void methodWithEventAppenderSubtypeParameter(
            CustomEventAppender customEventAppender
    ) {
        // This method is used to test the EventAppenderParameterResolverFactory
    }

    /**
     * A subtype of {@link EventAppender} that this factory cannot instantiate, mirroring
     * {@code EventStoreAppender} from the eventsourcing module: only its own dedicated resolver should claim
     * parameters of this type.
     */
    private interface CustomEventAppender extends EventAppender {

    }
}