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
package events.eventhandlers.messagestream;

import java.util.concurrent.CompletableFuture;

// tag::event-handler-messagestream-return[]
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;

public class AxonConfig {

    // omitted event processing configurer methods...

    private EventHandlingComponentsConfigurer.AdditionalComponentPhase configureHandlingComponent(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.declarative("order-handler", c -> {
            SimpleEventHandlingComponent eventHandlingComponent = SimpleEventHandlingComponent.create("order-handler");
            eventHandlingComponent.subscribe(
                    new QualifiedName("OrderPlaced"),
                    (event, context) -> {
                        OrderPlacedEvent eventPayload = event.payloadAs(OrderPlacedEvent.class);
                        // process events
                        return MessageStream.empty();
                    }
            );
            eventHandlingComponent.subscribe(
                    new QualifiedName("OrderDeclined"),
                    (event, context) -> {
                        OrderDeclinedEvent eventPayload = event.payloadAs(OrderDeclinedEvent.class);
                        AsyncService asyncService = context.component(AsyncService.class);
                        return MessageStream.fromFuture(
                                asyncService.processOrderDeclined(eventPayload)
                                            .thenApply(r -> (Message) null)
                        ).ignoreEntries();
                    }
            );
            return eventHandlingComponent;
        });
    }
}
// end::event-handler-messagestream-return[]

record OrderPlacedEvent(String orderId) {

}

record OrderDeclinedEvent(String orderId, String reason) {

}

interface AsyncService {

    CompletableFuture<Void> processOrderDeclined(OrderDeclinedEvent event);
}
