package events.eventprocessors.streaming.segments.splitmerge;

// tag::split-merge-segment[]
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

class StreamingProcessorService {

    // The Configuration allows access to all the configured EventProcessors
    private Configuration configuration;

    CompletableFuture<Boolean> splitSegmentFor(String processorName, int segmentId) {
        Map<String, StreamingEventProcessor> processors = configuration.getComponents(StreamingEventProcessor.class);
        return processors.get(processorName)
                         .splitSegment(segmentId);
    }

    CompletableFuture<Boolean> mergeSegmentFor(String processorName, int segmentId) {
        Map<String, StreamingEventProcessor> processors = configuration.getComponents(StreamingEventProcessor.class);
        return processors.get(processorName)
                         .mergeSegment(segmentId);
    }
}
// end::split-merge-segment[]
