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
package events.eventprocessors.streaming.sequencingpolicy.processorwide;

// tag::processor-wide-sequencing-policy-defaults[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.sequencing.MetadataSequencingPolicy;

public class AxonConfig {

    public void configureProcessorWideSequencingPolicy(MessagingConfigurer configurer) {
        configurer.eventProcessing(eventConfigurer -> eventConfigurer.pooledStreaming(
                pooledStreamingConfigurer -> pooledStreamingConfigurer.defaults(
                        config -> config.sequencingPolicy(new MetadataSequencingPolicy("customerId"))
                )
        ));
    }
}
// end::processor-wide-sequencing-policy-defaults[]
