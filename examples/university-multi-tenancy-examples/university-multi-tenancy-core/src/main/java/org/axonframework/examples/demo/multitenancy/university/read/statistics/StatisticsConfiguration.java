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

package org.axonframework.examples.demo.multitenancy.university.read.statistics;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.demo.multitenancy.shared.DemoBacking;
import org.axonframework.examples.demo.multitenancy.shared.EventProcessingStyle;
import org.axonframework.examples.demo.multitenancy.shared.tenant.MultiTenantPersistentStreams;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorModule;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;

/**
 * Registers the tenant-statistics read slice: the {@link TenantStatisticsQueryHandler}, whose
 * {@code @TenantScoped} parameters the framework injects with the query tenant's components, and, on a backing
 * whose tenants each have their own event store, the {@link CourseStatisticsProjection} and the processor
 * running it. The declarative demo calls {@link #configure(EventSourcingConfigurer, DemoBacking, EventProcessingStyle)};
 * the Spring Boot demo declares the query handler as a bean and the processor through an
 * {@code EventProcessorDefinition}, since a module bean holding an event processor is ignored on that path.
 */
public final class StatisticsConfiguration {

    /**
     * The name of the one processor that projects every tenant's events. There is deliberately only one, and
     * it is named for what it projects rather than for any tenant, since the same processor serves them all.
     */
    public static final String PROCESSOR_NAME = "Projection_CourseStatistics_Processor";

    /**
     * The name the persistent stream carries in every tenant's Axon Server context, when the projection
     * processor runs in {@link EventProcessingStyle#PERSISTENT_STREAMS}.
     */
    public static final String PERSISTENT_STREAM_NAME = PROCESSOR_NAME + "-stream";

    private StatisticsConfiguration() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Registers the statistics query handling module on the given {@code configurer}, and, when the given
     * {@code backing} projects the read model, the projection processor running it in
     * {@link EventProcessingStyle#POOLED_STREAMING}.
     *
     * @param configurer the event sourcing configurer to register the read slice on
     * @param backing    what backs this run
     * @return the given {@code configurer}, for further configuring
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer,
                                                    DemoBacking backing) {
        return configure(configurer, backing, EventProcessingStyle.POOLED_STREAMING);
    }

    /**
     * Registers the statistics query handling module on the given {@code configurer}, and the projection
     * processor when the given {@code backing} projects the read model, run in the given {@code streamingMode}.
     *
     * @param configurer   the event sourcing configurer to register the read slice on
     * @param backing      what backs this run
     * @param streamingMode how the projection processor is fed, ignored unless {@code backing} projects the
     *                      read model
     * @return the given {@code configurer}, for further configuring
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer,
                                                    DemoBacking backing,
                                                    EventProcessingStyle streamingMode) {
        configurer = configurer.registerQueryHandlingModule(queryModule());
        if (backing.projectsReadModel()) {
            configurer = switch (streamingMode) {
                case POOLED_STREAMING -> configurer.modelling(
                        modelling -> modelling.messaging(
                                messaging -> messaging.eventProcessing(
                                        eventProcessing -> eventProcessing.pooledStreaming(
                                                pooled -> pooled.processor(pooledStreamingProcessorModule())))));
                case PERSISTENT_STREAMS -> configurer.modelling(
                        modelling -> modelling.messaging(
                                messaging -> messaging.eventProcessing(
                                        eventProcessing -> eventProcessing.subscribing(
                                                subscribing -> subscribing.processor(
                                                        persistentStreamProcessorModule())))));
            };
        }
        return configurer;
    }

    private static PooledStreamingEventProcessorModule pooledStreamingProcessorModule() {
        return EventProcessorModule.pooledStreaming(PROCESSOR_NAME)
                                   .eventHandlingComponents(
                                           components -> components.autodetected(
                                                   "courseStatisticsProjection",
                                                   config -> new CourseStatisticsProjection()))
                                   .notCustomized();
    }

    /**
     * Builds the same projection processor over a persistent stream, one per tenant fanned into this single
     * subscribing processor by {@link MultiTenantPersistentStreams#eventSource}.
     */
    private static SubscribingEventProcessorModule persistentStreamProcessorModule() {
        return EventProcessorModule.subscribing(PROCESSOR_NAME)
                                   .eventHandlingComponents(
                                           components -> components.autodetected(
                                                   "courseStatisticsProjection",
                                                   config -> new CourseStatisticsProjection()))
                                   .customized((configuration, subscribing) -> subscribing.eventSource(
                                           MultiTenantPersistentStreams.eventSource(PERSISTENT_STREAM_NAME,
                                                                                    configuration)));
    }

    private static QueryHandlingModule queryModule() {
        return QueryHandlingModule.named("tenant-statistics")
                                  .queryHandlers()
                                  .autodetectedQueryHandlingComponent(config -> new TenantStatisticsQueryHandler())
                                  .build();
    }
}
