package events.eventprocessors.streaming.tokenstoreidentifier;

// tag::token-store-identifier[]
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;

public class AxonConfig {

    public String tokenStoreFor(StreamingEventProcessor eventProcessor) {
        return eventProcessor.getTokenStoreIdentifier();
    }
}
// end::token-store-identifier[]
