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

import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.extension.micronaut.authorization.SecuredMessageHandlerDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration class that registers a bean creation method for the {@link SecuredMessageHandlerDefinition}.
 * <p>
 * Only triggered if the {@code org.springframework.security.access.annotation.Secured} class is on the classpath.
 *
 * @author Roald Bankras
 * @since 4.11.0
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.security.access.annotation.Secured")
public class SecurityAutoConfiguration {

    /**
     * Bean creation method constructing a {@link SecuredMessageHandlerDefinition} allowing for secured message
     * handlers.
     *
     * @return The {@link HandlerEnhancerDefinition} allowing for secured message handlers.
     */
    @Bean
    public HandlerEnhancerDefinition securedMessageHandlerDefinition() {
        return new SecuredMessageHandlerDefinition();
    }
}