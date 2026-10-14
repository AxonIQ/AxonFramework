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
package queries.querydispatchers.shutdown.springboot;

import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryPriorityCalculator;
import org.axonframework.messaging.queryhandling.QueryShutdownManager;
import org.axonframework.messaging.queryhandling.gateway.DefaultQueryGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.queryhandling.gateway.ShutdownTrackingQueryGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueryShutdownConfiguration {

    // tag::query-shutdown-manager-bean[]
    @Bean
    public QueryShutdownManager queryShutdownManager() {
        return QueryShutdownManager.closeImmediately();
    }
    // end::query-shutdown-manager-bean[]

    // tag::named-gateway-bean[]
    @Bean
    public QueryGateway sseGateway(QueryBus queryBus,
                                   MessageTypeResolver resolver,
                                   QueryPriorityCalculator calculator,
                                   MessageConverter converter,
                                   QueryShutdownManager shutdownManager) { // <1>
        DefaultQueryGateway base = new DefaultQueryGateway(queryBus, resolver, calculator, converter);
        return ShutdownTrackingQueryGateway.build(base, shutdownManager, shutdownManager); // <2>
    }
    // end::named-gateway-bean[]
}
