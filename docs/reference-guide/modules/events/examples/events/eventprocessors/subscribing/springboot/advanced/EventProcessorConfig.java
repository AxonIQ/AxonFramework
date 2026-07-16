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
package events.eventprocessors.subscribing.springboot.advanced;

// tag::event-processor-definition-advanced[]
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.errorhandling.PropagatingErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventProcessorConfig {

    @Bean
    public EventProcessorDefinition advancedProcessorDefinition(
            EventBus eventBus,
            MessageHandlerInterceptor<? super EventMessage> interceptor) {
        return EventProcessorDefinition.subscribing("advanced-processor")
                .assigningHandlers(descriptor ->
                    descriptor.beanName().startsWith("advanced"))
                .customized(config -> config
                    .eventSource(eventBus)
                    .withInterceptor(interceptor)
                    .errorHandler(PropagatingErrorHandler.INSTANCE));
    }
}
// end::event-processor-definition-advanced[]
