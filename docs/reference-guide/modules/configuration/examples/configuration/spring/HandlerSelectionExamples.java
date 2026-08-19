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

// tag::handler-selection-examples[]
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class HandlerSelectionExamples {

    @Bean
    public EventProcessorDefinition byBeanNamePrefix() {
        // By bean name prefix
        return EventProcessorDefinition.pooledStreaming("order-processor")
                .assigningHandlers(d -> d.beanName().startsWith("order"))
                .notCustomized();
    }

    @Bean
    public EventProcessorDefinition byPackage() {
        // By package
        return EventProcessorDefinition.pooledStreaming("orders-package-processor")
                .assigningHandlers(d -> d.beanType().getPackageName().startsWith("com.example.orders"))
                .notCustomized();
    }

    @Bean
    public EventProcessorDefinition byBeanNamePattern() {
        // By bean name pattern
        return EventProcessorDefinition.pooledStreaming("handler-processor")
                .assigningHandlers(d -> d.beanName().contains("Handler"))
                .notCustomized();
    }
}
// end::handler-selection-examples[]
