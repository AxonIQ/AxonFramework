package events.eventprocessors.streaming.replay.resetwithcontext;

// tag::reset-tokens-with-context[]
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

class StreamingProcessorService {

    // The Configuration allows access to all the configured EventProcessors
    private Configuration configuration;

    CompletableFuture<Void> resetTokensFor(String processorName,
                                           Object resetContext) {
        Map<String, StreamingEventProcessor> processors = configuration.getComponents(StreamingEventProcessor.class);
        StreamingEventProcessor processor = processors.get(processorName);
        // shutdown this streaming processor
        return processor.shutdown()
                        // reset the tokens to prepare the processor
                        .thenCompose(result -> processor.resetTokens(resetContext))
                        // start the processor to initiate the replay
                        .thenCompose(result -> processor.start());
    }
}
// end::reset-tokens-with-context[]
