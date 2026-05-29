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

package org.axonframework.examples.demo.coursecatalog.catalog;

import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import org.axonframework.examples.demo.coursecatalog.catalog.transformations.CourseCatalogTransformations;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

/**
 * Wires the course-catalog bounded context into an {@link EventSourcingConfigurer}.
 * Registering the chain as a component is enough to engage the framework's
 * {@code EventTransformationConfigurationEnhancer}, which discovers it via
 * {@code ServiceLoader} and installs the {@code TransformingEventStore} decorator.
 */
public final class CourseCatalogModuleConfiguration {

    private CourseCatalogModuleConfiguration() {
    }

    /**
     * @param configurer the configurer to extend
     * @return the configurer with the catalog chain registered
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.componentRegistry(
                registry -> registry.registerComponent(EventTransformerChain.class,
                                                       config -> CourseCatalogTransformations.chain())
        );
    }
}
