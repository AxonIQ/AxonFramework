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

package org.axonframework.extension.micronaut.messaging;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;
import static org.junit.jupiter.api.Assertions.*;

@Property(name = "spec.name", value = "ApplicationContextEventPublisherTest")
@MicronautTest(startApplication = false)
public class ApplicationContextEventPublisherTest {

    @Inject
    public ListenerBean listenerBean;

    @Inject
    public EventBus eventBus;

    @Test
    void eventsForwardedToListenerBean() throws InterruptedException {
        eventBus.publish(null, asEventMessage("test"));
        Thread.sleep(1000);

        assertEquals("test", listenerBean.getEvents().getFirst());
    }

    @Requires(property = "spec.name", value = "ApplicationContextEventPublisherTest")
    @Factory
    public static class EventBusFactory {

        @Singleton
        public EventBus simpleEventBus() {
            return new SimpleEventBus();
        }
    }

    @Requires(property = "spec.name", value = "ApplicationContextEventPublisherTest")
    @Singleton
    public static class ListenerBean {

        private final List<Object> events = new ArrayList<>();

        public ListenerBean() {
        }

        @EventListener
        public void handle(String event) {
            events.add(event);
        }

        public List<Object> getEvents() {
            return events;
        }
    }
}
