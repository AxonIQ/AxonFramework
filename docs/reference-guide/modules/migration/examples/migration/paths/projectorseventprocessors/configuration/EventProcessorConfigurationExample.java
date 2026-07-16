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
package migration.paths.projectorseventprocessors.configuration;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;

// tag::event-processor-configuration[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;

public class EventProcessorConfigurationExample {

    public void configure(MessagingConfigurer configurer) {
        configurer.eventProcessing(
                eventProcessing -> eventProcessing.pooledStreaming(
                        pooledStreaming -> pooledStreaming.processor(
                                "my-processor",
                                module -> module.eventHandlingComponents(
                                                         components -> components.autodetected(
                                                                 "my-projector",
                                                                 cfg -> new MyProjector()
                                                         )
                                                 )
                                                 .notCustomized()
                        )
                )
        );
    }
}
// end::event-processor-configuration[]

class MyProjector {

    @EventHandler
    public void on(MyEvent event) {
        // ...
    }
}

record MyEvent() {

}
