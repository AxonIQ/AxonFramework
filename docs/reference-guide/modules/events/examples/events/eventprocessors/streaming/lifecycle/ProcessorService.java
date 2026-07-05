package events.eventprocessors.streaming.lifecycle;

// tag::start-and-query-identifier[]
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;

public class ProcessorService {

    private final AxonConfiguration configuration;

    public ProcessorService(AxonConfiguration configuration) {
        this.configuration = configuration;
    }

    public void startAndQueryIdentifier(String processorName) {
        StreamingEventProcessor processor =
                configuration.getComponent(StreamingEventProcessor.class, processorName);
        processor.start()
                 .thenRun(() -> {
                     String identifier = processor.getTokenStoreIdentifier();
                     System.out.println("Token store identifier: " + identifier);
                 });
    }
}
// end::start-and-query-identifier[]
