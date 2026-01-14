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

package org.axonframework.extension.micronaut.autoconfig;

import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.extension.micronaut.actuator.axonserver.AxonServerHealthIndicator;
import org.axonframework.extension.micronaut.actuator.axonserver.AxonServerStatusAggregator;
import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration class for Spring Boot Actuator monitoring tools around Axon Server.
 *
 * @author Steven van Beelen
 * @since 4.6.0
 */
@AutoConfiguration(after = AxonServerAutoConfiguration.class)
@ConditionalOnClass(name = {
        "org.springframework.boot.actuate.health.AbstractHealthIndicator",
        "org.axonframework.axonserver.connector.AxonServerConnectionManager"
})
@ConditionalOnProperty(name = "axon.axonserver.enabled", matchIfMissing = true)
public class AxonServerActuatorAutoConfiguration {

    @ConditionalOnMissingBean(AxonServerHealthIndicator.class)
    @Bean
    public AxonServerHealthIndicator axonServerHealthIndicator(AxonServerConnectionManager connectionManager) {
        return new AxonServerHealthIndicator(connectionManager);
    }
    @ConditionalOnMissingBean(SimpleStatusAggregator.class)
    @Bean
    public AxonServerStatusAggregator axonServerStatusAggregator() {
        return new AxonServerStatusAggregator();
    }
}
