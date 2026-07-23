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

package org.axonframework.examples.demo.multitenancy.university.read.statistics;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;

/**
 * Registers the tenant-statistics read slice: the {@link TenantStatisticsQueryHandler}, whose
 * {@code @TenantScoped} parameters the framework injects with the query tenant's components. The
 * declarative demo calls {@link #configure(EventSourcingConfigurer)}; the Spring Boot demo instead
 * declares the query handler as a bean the starter picks up.
 */
public final class StatisticsConfiguration {

    private StatisticsConfiguration() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Registers the statistics query handling module on the given {@code configurer}.
     *
     * @param configurer the event sourcing configurer to register the read slice on
     * @return the given {@code configurer}, for further configuring
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        QueryHandlingModule queryModule =
                QueryHandlingModule.named("tenant-statistics")
                                   .queryHandlers()
                                   .autodetectedQueryHandlingComponent(config -> new TenantStatisticsQueryHandler())
                                   .build();
        return configurer.registerQueryHandlingModule(queryModule);
    }
}
