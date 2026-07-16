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
package root.springbootintegration;

// The import block below is indented to the depth of the surrounding class body, so
// that indent=0 normalization on the include renders the combined regions flush left.
// tag::namespace-processor-import[]
    import org.axonframework.extension.spring.config.EventHandlerSelector;
    import org.axonframework.extension.spring.config.EventProcessorDefinition;

// end::namespace-processor-import[]
import org.axonframework.messaging.core.annotation.Namespace;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NamespaceBasedProcessorConfig {

    // tag::namespace-processor[]
    @Bean
    public EventProcessorDefinition ordersProcessor() {
        return EventProcessorDefinition.pooledStreaming("orders")
                .assigningHandlers(EventHandlerSelector.matchesNamespaceOnType("orders"))
                .notCustomized();
    }

    // end::namespace-processor[]
    // tag::namespace-event-handler[]
    @Namespace("orders")
    public class OrderEventHandler {
        // omitted event handlers for brevity
    }
    // end::namespace-event-handler[]
}
