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
package tuning.eventprocessing;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;

public class PooledStreamingProcessorConfiguration {

    private final EventHandlingComponent orderProjection = SimpleEventHandlingComponent.create("order-projection");

    // tag::configure-pooled-streaming[]
    public void configureEventProcessing(MessagingConfigurer configurer) {

        configurer.eventProcessing(eventProcessing -> eventProcessing
                               .pooledStreaming(pooled -> pooled
                                      .defaults((configuration, processorConfig) -> processorConfig
                                               .initialSegmentCount(8)
                                               .batchSize(50)
                                               .tokenClaimInterval(5000)
                                               .claimExtensionThreshold(5000))
                                       .defaultProcessor("order-processor", components ->
                                               components.declarative("OrderUpdated", cfg -> orderProjection))));
    }
    // end::configure-pooled-streaming[]
}
