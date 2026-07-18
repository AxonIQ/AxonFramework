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
package messagingconcepts.messageintercepting.query;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;

public class QueryInterceptorConfig {

    // tag::register-query-dispatch[]
    public void registerQueryDispatchInterceptor(MessagingConfigurer configurer) {
        configurer.registerQueryDispatchInterceptor(
                config -> new QueryLoggingDispatchInterceptor()
        );
    }
    // end::register-query-dispatch[]

    // tag::register-query-handler[]
    public void registerQueryHandlerInterceptor(MessagingConfigurer configurer) {
        configurer.registerQueryHandlerInterceptor(
                config -> new QuerySecurityInterceptor()
        );
    }
    // end::register-query-handler[]
}
