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
package commands.configuration.interceptors.handler;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// tag::handler-interceptor-spring[]
@Configuration
public class InterceptorConfig {

    @Bean
    public MessageHandlerInterceptor<? super CommandMessage> securityInterceptor() {
        return (command, context, interceptorChain) -> {
            checkPermissions(command);
            return interceptorChain.proceed(command, context);
        };
    }
    // end::handler-interceptor-spring[]

    private void checkPermissions(Object command) {
        // Verify the caller may issue this command.
    }
    // tag::handler-interceptor-spring[]
}
// end::handler-interceptor-spring[]
