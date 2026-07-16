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
