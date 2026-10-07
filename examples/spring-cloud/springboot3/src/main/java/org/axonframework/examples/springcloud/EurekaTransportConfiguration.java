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

package org.axonframework.examples.springcloud;

import com.netflix.discovery.shared.transport.jersey3.Jersey3TransportClientFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Jakarta/Jersey 3 Eureka transport client explicitly.
 * <p>
 * {@code com.netflix.eureka:eureka-client} pulls plain Jersey client libraries onto the classpath transitively,
 * which trips a known {@code spring-cloud-netflix-eureka-client} issue
 * (<a href="https://github.com/spring-cloud/spring-cloud-netflix/issues/4266">spring-cloud/spring-cloud-netflix#4266</a>):
 * without an explicit {@link Jersey3TransportClientFactories} bean, {@code EurekaRegistration} ends up with a
 * {@code null} Eureka client and every node fails to start.
 */
@Configuration(proxyBeanMethods = false)
class EurekaTransportConfiguration {

    @Bean
    Jersey3TransportClientFactories jersey3TransportClientFactories() {
        return new Jersey3TransportClientFactories();
    }
}
