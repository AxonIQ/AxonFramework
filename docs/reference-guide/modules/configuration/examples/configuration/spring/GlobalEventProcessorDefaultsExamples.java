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
package configuration.spring;

import org.axonframework.messaging.eventhandling.processing.errorhandling.PropagatingErrorHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class GlobalEventProcessorDefaultsExamples {

    // tag::global-customizations[]
    @Configuration
    static class GlobalEventProcessorDefaults {

        @Bean
        PooledStreamingEventProcessorModule.Customization pooledDefaults() {
            return (configuration, processorConfiguration) -> processorConfiguration
                    .errorHandler(PropagatingErrorHandler.INSTANCE)
                    .batchSize(50);
        }

        @Bean
        SubscribingEventProcessorModule.Customization subscribingDefaults() {
            return (configuration, processorConfiguration) -> processorConfiguration
                    .errorHandler(PropagatingErrorHandler.INSTANCE);
        }
    }
    // end::global-customizations[]
}
