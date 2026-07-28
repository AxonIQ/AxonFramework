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
package monitoring.tracing;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.tracing.LoggingSpanFactory;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.configuration.MessagingTracingSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class TracingConfiguration {

    // tag::plain-java[]
    public AxonConfiguration configureTracing() {
        MessagingTracingSettings tracingSettings = MessagingTracingSettings.enabledByDefault()
                                                                            .withEventProcessorDistributedInSameTrace(
                                                                                    true
                                                                            );
        return MessagingConfigurer.create()
                                  .componentRegistry(registry -> registry
                                          .registerComponent(
                                                  SpanFactory.class,
                                                  config -> LoggingSpanFactory.INSTANCE
                                          )
                                          .registerComponent(
                                                  MessagingTracingSettings.class,
                                                  config -> tracingSettings
                                          ))
                                  .start();
    }
    // end::plain-java[]

    // tag::spring-boot-logging[]
    @Configuration
    static class SpringTracingConfiguration {

        @Bean
        SpanFactory spanFactory() {
            return LoggingSpanFactory.INSTANCE;
        }
    }
    // end::spring-boot-logging[]
}
