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
package queries.querydispatchers.shutdown;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.QueryShutdownManager;
import org.axonframework.messaging.queryhandling.configuration.QueryGatewayConfigurer;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;

import java.time.Duration;

class GatewayTrackingExample {

    void namedGatewayWithShutdownTracking() {
        // tag::gateway-level-tracking[]
        AxonConfiguration config = MessagingConfigurer.create()
            .registerQueryGateway("sse", g -> g
                .cancellingSubscriptionQueryOnShutdown() // <1>
                .cancellingStreamingQueryOnShutdown(Duration.ofSeconds(5)) // <2>
            )
            .build();

        QueryGateway sseGateway = config.getComponent(QueryGateway.class, "sse"); // <3>
        // end::gateway-level-tracking[]
    }

    void plainNamedGateway() {
        // tag::plain-named-gateway[]
        MessagingConfigurer.create()
            .registerQueryGateway("reporting", QueryGatewayConfigurer::withDefaults);
        // end::plain-named-gateway[]
    }

    void multipleGateways() {
        // tag::multiple-gateways[]
        MessagingConfigurer.create()
            .registerQueryGateway("sse", g -> g
                .cancellingSubscriptionQueryOnShutdown()
            )
            .registerQueryGateway("internal", g -> g
                .cancellingSubscriptionQueryOnShutdown(Duration.ofSeconds(30))
            );
        // end::multiple-gateways[]
    }

    void sharedManager() {
        // tag::shared-manager[]
        QueryShutdownManager manager = QueryShutdownManager.withGracePeriod(Duration.ofSeconds(10));

        MessagingConfigurer.create()
            .registerQueryGateway("sse", g -> g.cancellingSubscriptionQueryOnShutdown(manager))
            .registerQueryGateway("internal", g -> g.cancellingSubscriptionQueryOnShutdown(manager));
        // end::shared-manager[]
    }
}
