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
