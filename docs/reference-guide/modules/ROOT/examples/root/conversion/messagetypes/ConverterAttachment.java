package root.conversion.messagetypes;

import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;

class ConverterAttachment {

    GenericEventMessage attachConverter(GenericEventMessage rawMessage, EventConverter eventConverter) {
        // tag::attach-converter-to-message[]
        GenericEventMessage messageWithConverter = rawMessage.withConverter(eventConverter);
        // end::attach-converter-to-message[]
        return messageWithConverter;
    }
}
