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

package org.axonframework.config;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.modelling.saga.AbstractResourceInjector;

import java.util.Optional;

/**
 * ResourceInjector implementation that injects resources defined in the Axon Configuration.
 */
public class ConfigurationResourceInjector extends AbstractResourceInjector {

    private final Configuration configuration;

    /**
     * Initializes the ResourceInjector to inject the resources found in the given {@code configuration}.
     *
     * @param configuration the Configuration to find injectable resources in
     */
    public ConfigurationResourceInjector(Configuration configuration) {
        this.configuration = configuration;
    }

    @Override
    protected <R> Optional<R> findResource(Class<R> requiredType) {
        return configuration.getOptionalComponent(requiredType);
    }
}
