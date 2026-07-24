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
package messagingconcepts.anatomymessage;

import com.fasterxml.jackson.databind.JsonNode;
import org.axonframework.conversion.Converter;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

class PayloadAccess {

    // tag::accessing-payload[]
    public void retrievePayload(Message message) {
        // Get the payload as-is
        Object payload = message.payload();

        // Get the payload converted to a specific type
        OrderPlacedEvent event = message.payloadAs(OrderPlacedEvent.class);

        // Get the payload type
        Class<?> type = message.payloadType();
    }
    // end::accessing-payload[]

    // tag::payload-conversion-aware[]
    @EventHandler
    public void on(EventMessage event) {
        // Converter is already attached, no need to pass it explicitly
        OrderPlacedEvent order = event.payloadAs(OrderPlacedEvent.class);
    }
    // end::payload-conversion-aware[]

    // tag::explicit-converter[]
    public void retrieveConvertedPayload(Message message, Converter converter) {
        OrderPlacedEvent payload = message.payloadAs(OrderPlacedEvent.class, converter);
    }
    // end::explicit-converter[]

    void attachConverter(GenericEventMessage rawMessage, Converter eventConverter) {
        // tag::attach-converter[]
        GenericEventMessage messageWithConverter = rawMessage.withConverter(eventConverter);
        // end::attach-converter[]
    }

    // tag::convert-payload[]
    public void convertEventPayload(EventMessage event, Converter converter) {
        // Create a new message with JSON payload
        EventMessage jsonMessage = event.withConvertedPayload(
                JsonNode.class,
                converter
        );
    }
    // end::convert-payload[]

    void readIdentifier(Message message) {
        // tag::message-identifier[]
        String id = message.identifier();
        // end::message-identifier[]
    }
}
