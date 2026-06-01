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

package org.axonframework.examples.demo.coursecatalog.catalog.automation.overbookingnotifier;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.shared.notifier.LoggingNotificationService;
import org.axonframework.examples.demo.coursecatalog.shared.notifier.NotificationService;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;

/**
 * Wires the {@link OverbookingNotifier} into a dedicated pooled-streaming
 * processor scoped by {@code courseId} so concurrent published courses don't
 * compete for the same handler thread. Also registers a default
 * {@link NotificationService} unless one is already present (allowing tests to
 * substitute a recording implementation).
 */
public final class OverbookingNotifierConfiguration {

    private OverbookingNotifierConfiguration() {
    }

    /**
     * @param configurer the configurer to extend
     * @return the configurer with the automation registered
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        PooledStreamingEventProcessorModule automationProcessor = EventProcessorModule
                .pooledStreaming("Automation_OverbookingNotifier_Processor")
                .eventHandlingComponents(c -> c.declarative("OverbookingNotifier", cfg -> SimpleEventHandlingComponent.create(
                        "OverbookingNotifier",
                        new PropertySequencingPolicy<>(
                                CoursePublished.class,
                                "courseId"
                        )
                ).subscribe(new QualifiedName(CourseCatalogMessageNames.COURSE_PUBLISHED),
                            OverbookingNotifier::react)))
                .notCustomized();

        return configurer
                .componentRegistry(cr -> cr.registerIfNotPresent(NotificationService.class,
                                                                 cfg -> new LoggingNotificationService()))
                .modelling(modelling -> modelling.messaging(messaging -> messaging.eventProcessing(
                        eventProcessing -> eventProcessing.pooledStreaming(ps -> ps.processor(automationProcessor)))));
    }
}
