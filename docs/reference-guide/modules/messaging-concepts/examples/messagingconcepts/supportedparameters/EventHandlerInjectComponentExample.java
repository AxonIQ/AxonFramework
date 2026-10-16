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
package messagingconcepts.supportedparameters;

import java.util.concurrent.CompletableFuture;

import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.annotation.InjectComponent;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class EventHandlerInjectComponentExample {

    // tag::register-named-event-sink[]
    void configure(MessagingConfigurer configurer) {
        configurer.componentRegistry(registry -> registry.registerComponent(
                EventSink.class, "auditSink", config -> new AuditEventSink()
        ));
    }
    // end::register-named-event-sink[]

    // tag::spring-named-event-sink[]
    @Configuration
    static class AuditConfiguration {

        @Bean
        @Qualifier("auditSink")
        EventSink kafkaAuditSink() {
            return new AuditEventSink();
        }
    }
    // end::spring-named-event-sink[]

    // tag::event-handler-inject-component[]
    @EventHandler
    public CompletableFuture<Void> handle(OrderPlacedEvent event,
                                          @InjectComponent(name = "auditSink") EventSink auditSink,
                                          ProcessingContext context) {
        // Publish to the "auditSink", instead of the default EventSink
        OrderAuditedEvent audited = new OrderAuditedEvent(event.orderId());
        return auditSink.publish(context, new GenericEventMessage(new MessageType(OrderAuditedEvent.class), audited));
    }
    // end::event-handler-inject-component[]

    record OrderAuditedEvent(String orderId) {

    }
}
