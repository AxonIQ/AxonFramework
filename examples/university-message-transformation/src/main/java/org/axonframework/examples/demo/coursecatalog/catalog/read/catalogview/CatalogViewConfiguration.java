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

package org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;

/**
 * Wires the catalog view's read slice into an {@link EventSourcingConfigurer}:
 * registers the in-memory repository, the pooled-streaming projection processor,
 * and the query-handler module.
 */
public final class CatalogViewConfiguration {

    private CatalogViewConfiguration() {
    }

    /**
     * @param configurer the configurer to extend
     * @return the configurer with the read slice registered
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        PooledStreamingEventProcessorModule projectionProcessor = EventProcessorModule
                .pooledStreaming("Projection_CatalogView_Processor")
                .eventHandlingComponents(
                        c -> c.autodetected(cfg -> new CatalogViewProjection(
                                cfg.getComponent(CatalogViewRepository.class))))
                .notCustomized();

        QueryHandlingModule getCatalogViewHandler = QueryHandlingModule.named("get-course-catalog-view")
                                                                       .queryHandlers()
                                                                       .autodetectedQueryHandlingComponent(
                                                                               cfg -> new GetCourseCatalogViewQueryHandler(
                                                                                       cfg.getComponent(CatalogViewRepository.class)))
                                                                       .build();

        return configurer
                .componentRegistry(cr -> cr.registerComponent(
                        CatalogViewRepository.class,
                        cfg -> new InMemoryCatalogViewRepository()))
                .registerQueryHandlingModule(getCatalogViewHandler)
                .modelling(modelling -> modelling.messaging(messaging -> messaging.eventProcessing(
                        eventProcessing -> eventProcessing.pooledStreaming(ps -> ps.processor(projectionProcessor)))));
    }
}
