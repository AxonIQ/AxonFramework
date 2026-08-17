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
package configuration.module;

// tag::accessing-module-configurations-example[]
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

import java.util.List;
import java.util.Optional;

class AccessingModuleConfigurationsExample {

    void inspect() {
        AxonConfiguration configuration = EventSourcingConfigurer.create()
                                                                  // registration omitted
                                                                  .build();

        // get the configuration of a specific named module
        Optional<Configuration> ordersConfig = configuration.getModuleConfiguration("orders");

        // get all module configurations
        List<Configuration> allModules = configuration.getModuleConfigurations();
    }
}
// end::accessing-module-configurations-example[]
