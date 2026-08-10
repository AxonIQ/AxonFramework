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

package org.axonframework.eventsourcing.annotation;

import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreAppender;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Test class validating the {@link EventStoreAppenderParameterResolverFactory}.
 *
 * @author Mateusz Nowak
 */
class EventStoreAppenderParameterResolverFactoryTest {

    private final MessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();
    private final EventStore eventStore = new StorageEngineBackedEventStore(
            new InMemoryEventStorageEngine(), new SimpleEventBus(), event -> Set.of()
    );

    private final EventStoreAppenderParameterResolverFactory testSubject =
            new EventStoreAppenderParameterResolverFactory();

    @Test
    void injectsEventStoreAppenderBasedOnProcessingContext() throws Exception {
        ProcessingContext processingContext = StubProcessingContext.withComponents(registry -> {
            registry.registerComponent(EventStore.class, c -> eventStore);
            registry.registerComponent(EventBus.class, c -> eventStore);
            registry.registerComponent(MessageTypeResolver.class, c -> messageTypeResolver);
        });

        Method method = getClass().getMethod("methodWithEventStoreAppenderParameter", EventStoreAppender.class);
        ParameterResolver<?> instance = testSubject.createInstance(method, method.getParameters(), 0);
        assertNotNull(instance);
        Object injectedParameter = instance.resolveParameterValue(processingContext).join();
        assertInstanceOf(EventStoreAppender.class, injectedParameter);
    }

    @Test
    void doesNotInjectIntoThePlainEventAppenderSupertype() throws Exception {
        Method method = getClass().getMethod("methodWithEventAppenderParameter", EventAppender.class);
        ParameterResolver<?> instance = testSubject.createInstance(method, method.getParameters(), 0);
        assertNull(instance);
    }

    @Test
    void doesNotInjectIntoGenericParameter() throws Exception {
        Method method = getClass().getMethod("methodWithOtherParameter", Object.class);
        ParameterResolver<?> instance = testSubject.createInstance(method, method.getParameters(), 0);
        assertNull(instance);
    }

    public void methodWithEventStoreAppenderParameter(
            EventStoreAppender eventStoreAppender
    ) {
        // This method is used to test the EventStoreAppenderParameterResolverFactory
    }

    public void methodWithEventAppenderParameter(
            EventAppender eventAppender
    ) {
        // This method is used to test the EventStoreAppenderParameterResolverFactory
    }

    public void methodWithOtherParameter(
            Object otherParameter
    ) {
        // This method is used to test the EventStoreAppenderParameterResolverFactory
    }
}
