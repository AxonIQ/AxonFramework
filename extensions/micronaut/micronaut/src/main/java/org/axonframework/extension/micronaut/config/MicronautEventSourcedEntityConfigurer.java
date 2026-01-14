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

package org.axonframework.extension.micronaut.config;

import io.micronaut.context.annotation.Parameter;
import jakarta.annotation.Nonnull;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;

/**
 * A {@link ConfigurationEnhancer} implementation that will configure an Aggregate with the Axon
 * {@link Configuration}.
 *
 * @param <T>  The type of Aggregate to configure
 * @param <ID> The type of Aggregate id to configure
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@Internal
public class MicronautEventSourcedEntityConfigurer<ID, T> implements ConfigurationEnhancer {

    private final Class<T> entityType;
    private final Class<ID> idType;

    /**
     * Initializes a {@link ConfigurationEnhancer} for given {@code entityType} and {@code idType}.
     *
     * @param entityType The declared type of the entity.
     * @param idType        The type of id.
     */
    public MicronautEventSourcedEntityConfigurer(@Parameter @Nonnull Class<T> entityType,@Parameter @Nonnull Class<ID> idType) {
        this.entityType = entityType;
        this.idType = idType;
    }

    @Override
    public void enhance(@Nonnull ComponentRegistry registry) {
        var eventSourcedEntityModule = EventSourcedEntityModule.autodetected(this.idType, this.entityType);
        registry.registerModule(eventSourcedEntityModule);
    }
}
