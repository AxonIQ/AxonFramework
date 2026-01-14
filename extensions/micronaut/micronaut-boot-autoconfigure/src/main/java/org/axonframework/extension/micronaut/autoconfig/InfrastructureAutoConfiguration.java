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

import org.axonframework.extension.micronaut.config.MessageHandlerLookup;
import org.axonframework.extension.micronaut.config.MicronautEventSourcedMethodProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

/**
 * Infrastructure autoconfiguration class for Axon Framework application. Constructs the look-up components, like the
 * {@link MessageHandlerLookup} and {@link MicronautEventSourcedMethodProcessor} to find Axon components and register them with the
 * corresponding configuration enhancers.
 *
 * @author Allard Buijze
 * @since 3.0.4
 */
@AutoConfiguration
public class InfrastructureAutoConfiguration {

    /**
     * Provides Spring message handler lookup.
     *
     * @return The lookup for annotations for later message handling registrations.
     */
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @Bean
    public static MessageHandlerLookup messageHandlerLookup() {
        return new MessageHandlerLookup();
    }

    /**
     * Provides a Spring aggregate lookup.
     *
     * @return The lookup scanning for annotations for later entity registrations.
     */
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @Bean
    public static MicronautEventSourcedMethodProcessor springEventSourcedEntityLookup() {
        return new MicronautEventSourcedMethodProcessor();
    }
}
