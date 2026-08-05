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

package org.axonframework.examples.demo.multitenancy.university;

import org.axonframework.examples.demo.multitenancy.shared.DemoBacking;
import org.axonframework.examples.demo.multitenancy.shared.EventProcessingStyle;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.StatisticsConfiguration;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.EnrollStudentConfiguration;
import org.axonframework.examples.demo.multitenancy.university.write.opencourse.OpenCourseConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

/**
 * Registers the whole university domain on an {@link EventSourcingConfigurer} by delegating to each
 * slice's own configuration, the way {@code FacultyModuleConfiguration} does in the plain university
 * example. The declarative demo calls this directly; the Spring Boot demo instead exposes each slice's
 * modules as beans, so both wire the identical slices two ways.
 */
public final class UniversityModuleConfiguration {

    private UniversityModuleConfiguration() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Registers the write slices and the statistics read slice on the given {@code configurer}, with the
     * projection processor run in {@link EventProcessingStyle#POOLED_STREAMING}.
     *
     * @param configurer the event sourcing configurer to register the domain on
     * @param backing    what backs this run
     * @return the given {@code configurer}, for further configuring
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer,
                                                    DemoBacking backing) {
        return configure(configurer, backing, EventProcessingStyle.POOLED_STREAMING);
    }

    /**
     * Registers the write slices and the statistics read slice on the given {@code configurer}.
     * <p>
     * The given {@code backing} settles the one thing the two slices have to agree on: whichever of
     * them fills the read model, the other leaves it alone, so an enrollment is never counted twice. The given
     * {@code streamingMode} settles how that projection processor is fed, and only matters where one runs at all.
     *
     * @param configurer    the event sourcing configurer to register the domain on
     * @param backing       what backs this run
     * @param streamingMode how the projection processor is fed, where the {@code backing} runs one
     * @return the given {@code configurer}, for further configuring
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer,
                                                    DemoBacking backing,
                                                    EventProcessingStyle streamingMode) {
        // Recorded on the configuration, so what assembles the run afterwards reads the same backing this was
        // configured with rather than being told again and risking a different answer.
        configurer = configurer.componentRegistry(registry -> registry.registerComponent(DemoBacking.class,
                                                                                         config -> backing));
        configurer = OpenCourseConfiguration.configure(configurer);
        configurer = EnrollStudentConfiguration.configure(configurer, backing);
        configurer = StatisticsConfiguration.configure(configurer, backing, streamingMode);
        return configurer;
    }
}
