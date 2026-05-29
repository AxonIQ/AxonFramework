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
import org.axonframework.examples.demo.coursecatalog.catalog.automation.overbookingnotifier.OverbookingNotifierConfiguration;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.CatalogViewConfiguration;
import org.axonframework.examples.demo.coursecatalog.catalog.seed.LegacyEventSeedConfiguration;
import org.axonframework.examples.demo.coursecatalog.catalog.transformations.CourseCatalogTransformations;
import org.axonframework.examples.demo.coursecatalog.catalog.write.enrollstudent.EnrollStudentConfiguration;
import org.axonframework.examples.demo.coursecatalog.catalog.write.publishcourse.PublishCourseConfiguration;
import org.axonframework.examples.demo.coursecatalog.catalog.write.updatecoursecapacity.UpdateCourseCapacityConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

/**
 * Wires the course-catalog bounded context into an {@link EventSourcingConfigurer}:
 * the transformation chain, read slice, and overbooking-notifier automation.
 * Registering the chain as a component is enough to engage the framework's
 * {@code EventTransformationConfigurationEnhancer}, which discovers it via
 * {@code ServiceLoader} and installs the {@code TransformingEventStore} decorator.
 */
public final class CourseCatalogModuleConfiguration {

    private CourseCatalogModuleConfiguration() {
    }

    /**
     * @param configurer the configurer to extend
     * @return the configurer with the catalog wired in
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        configurer = configurer.componentRegistry(registry -> registry
                .registerComponent(EventTransformerChain.class,
                                   config -> CourseCatalogTransformations.chain())
        );
        // Write slices
        configurer = PublishCourseConfiguration.configure(configurer);
        configurer = UpdateCourseCapacityConfiguration.configure(configurer);
        configurer = EnrollStudentConfiguration.configure(configurer);

        // Read slice
        configurer = CatalogViewConfiguration.configure(configurer);

        // Automation
        configurer = OverbookingNotifierConfiguration.configure(configurer);

        // Seed (idempotent)
        configurer = LegacyEventSeedConfiguration.configure(configurer);
        return configurer;
    }
}
