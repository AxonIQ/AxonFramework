package events.eventprocessors.streaming.segments.release;

// tag::release-segment[]
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

class StreamingProcessorService {

    // The Configuration allows access to all the configured EventProcessors
    private Configuration configuration;

    CompletableFuture<Void> releaseSegmentFor(String processorName, int segmentId) {
        Map<String, StreamingEventProcessor> processors = configuration.getComponents(StreamingEventProcessor.class);
        return processors.get(processorName)
                         .releaseSegment(segmentId);
    }
}
// end::release-segment[]
