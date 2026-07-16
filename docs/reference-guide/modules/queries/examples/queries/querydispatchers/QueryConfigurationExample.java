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
package queries.querydispatchers;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.SimpleQueryBus;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;

class QueryConfigurationExample {

    void configureQueryInfrastructure() {
        // tag::query-configuration-api[]
        MessagingConfigurer configurer = MessagingConfigurer.create();

        // QueryBus is registered by default, but can be customized
        configurer.registerQueryBus(config -> new SimpleQueryBus(config.getComponent(UnitOfWorkFactory.class)));

        Configuration configuration = configurer.start();
        QueryGateway queryGateway = configuration.getComponent(QueryGateway.class);
        QueryBus queryBus = configuration.getComponent(QueryBus.class);
        // end::query-configuration-api[]
    }
}
