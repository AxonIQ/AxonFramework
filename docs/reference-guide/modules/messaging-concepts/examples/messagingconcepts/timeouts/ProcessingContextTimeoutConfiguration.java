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
package messagingconcepts.timeouts;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.timeout.UnitOfWorkTimeoutInterceptorBuilder;

class ProcessingContextTimeoutConfiguration {

    // tag::processing-context-timeout-config[]
    public void configureTimeoutBehavior(MessagingConfigurer configurer) {
        // Register timeout interceptor for command bus
        configurer.registerCommandHandlerInterceptor(c -> {
            UnitOfWorkTimeoutInterceptorBuilder builder =
                    new UnitOfWorkTimeoutInterceptorBuilder(
                            "CommandBus",
                            30000,  // timeout in ms
                            25000,  // warning threshold in ms
                            1000    // warning interval in ms
                    );
            return builder.buildCommandInterceptor();
        });

        // Register timeout interceptor for query bus
        configurer.registerQueryHandlerInterceptor(c -> {
            UnitOfWorkTimeoutInterceptorBuilder builder =
                    new UnitOfWorkTimeoutInterceptorBuilder(
                            "QueryBus",
                            30000,  // timeout in ms
                            25000,  // warning threshold in ms
                            1000    // warning interval in ms
                    );
            return builder.buildQueryInterceptor();
        });

        // Register timeout interceptor for event handlers
        configurer.registerEventHandlerInterceptor(c -> {
            UnitOfWorkTimeoutInterceptorBuilder builder =
                    new UnitOfWorkTimeoutInterceptorBuilder(
                            "EventProcessor",
                            30000,  // timeout in ms
                            25000,  // warning threshold in ms
                            1000    // warning interval in ms
                    );
            return builder.buildEventInterceptor();
        });
    }
    // end::processing-context-timeout-config[]
}
