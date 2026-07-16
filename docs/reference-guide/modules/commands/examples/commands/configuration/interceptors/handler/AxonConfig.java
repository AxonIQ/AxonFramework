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

import java.lang.invoke.MethodHandles;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::handler-interceptor-config-api[]
public class AxonConfig {

    private static final Logger logger =
        LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static void main(String[] args) {
        MessagingConfigurer.create().registerCommandHandlerInterceptor(
                config -> (command, context, interceptorChain) -> {
                    checkPermissions(command);
                    return interceptorChain.proceed(command, context);
                }
        );
    }
    // end::handler-interceptor-config-api[]

    private static void checkPermissions(Object command) {
        // Verify the caller may issue this command.
    }
    // tag::handler-interceptor-config-api[]
}
// end::handler-interceptor-config-api[]
