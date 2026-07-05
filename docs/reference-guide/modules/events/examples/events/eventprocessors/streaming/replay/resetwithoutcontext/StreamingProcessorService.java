package events.eventprocessors.streaming.replay.resetwithoutcontext;

// tag::reset-tokens-without-context[]
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

class StreamingProcessorService {

    // The Configuration allows access to all the configured EventProcessors
    private Configuration configuration;

    CompletableFuture<Void> resetTokensFor(String processorName) {
        Map<String, StreamingEventProcessor> processors = configuration.getComponents(StreamingEventProcessor.class);
        StreamingEventProcessor processor = processors.get(processorName);
        // shutdown this streaming processor
        return processor.shutdown()
                        // reset the tokens to prepare the processor
                        .thenCompose(result -> processor.resetTokens())
                        // start the processor to initiate the replay
                        .thenCompose(result -> processor.start());
    }
}
// end::reset-tokens-without-context[]
