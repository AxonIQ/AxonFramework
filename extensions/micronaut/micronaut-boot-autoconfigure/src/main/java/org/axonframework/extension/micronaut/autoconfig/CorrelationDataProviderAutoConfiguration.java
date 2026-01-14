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

import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.axonframework.messaging.core.correlation.CorrelationDataProviderRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Optional;

/**
 * Autoconfiguration class dedicated to collection any
 * {@link CorrelationDataProvider CorrelationDataProviders}.
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
@AutoConfiguration
@AutoConfigureBefore(AxonAutoConfiguration.class)
public class CorrelationDataProviderAutoConfiguration {

    /**
     * Bean creation method for a {@link DecoratorDefinition} that registers
     * {@link CorrelationDataProvider CorrelationDataProviders} with the {@link CorrelationDataProviderRegistry}.
     *
     * @param correlationDataProviders The collection of available
     *                                 {@link CorrelationDataProvider CorrelationDataProviders} in this Application
     *                                 Context, if any.
     * @return A bean creation method for a {@link DecoratorDefinition} that registers
     * {@link CorrelationDataProvider CorrelationDataProviders} with the {@link CorrelationDataProviderRegistry}.
     */
    @Bean
    public DecoratorDefinition<CorrelationDataProviderRegistry, CorrelationDataProviderRegistry> providerDecorator(
            Optional<List<CorrelationDataProvider>> correlationDataProviders
    ) {
        return DecoratorDefinition.forType(CorrelationDataProviderRegistry.class)
                                  .with((config, name, delegate) -> {
                                      if (correlationDataProviders.isPresent()) {
                                          for (CorrelationDataProvider provider : correlationDataProviders.get()) {
                                              delegate = delegate.registerProvider(c -> provider);
                                          }
                                      }
                                      return delegate;
                                  });
    }
}
