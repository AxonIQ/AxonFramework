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

package org.axonframework.examples.demo.multitenancy.scaffolding;

import io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer;
import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.api.TenantProvider;
import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenancyConfigurationUtils.MultiTenancyEnabled;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.demo.multitenancy.university.UniversityModuleConfiguration;
import org.axonframework.examples.demo.multitenancy.university.read.coursestats.CourseStatsProjection;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The demo's configuration-time guardrail: registering two {@link TenantComponentProvider}s for the
 * same component type makes a handler parameter of that type ambiguous, so the framework refuses to
 * resolve it. This is driven through the normal event-processing path, so it fails exactly where a
 * real application would: registering the {@link CourseStatsProjection} and starting the configuration
 * triggers handler inspection, which cannot decide which provider feeds the projection's
 * {@code CourseStatsRepository} parameter.
 */
public final class ProviderAmbiguityGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(ProviderAmbiguityGuardrail.class);

    private ProviderAmbiguityGuardrail() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Builds a throwaway configuration that registers the {@link CourseStatsProjection} with two
     * providers for its {@code CourseStatsRepository} parameter, and checks that starting it is rejected
     * because the framework cannot know which provider to inject.
     *
     * @return {@code true} if the ambiguity was rejected with an {@link AxonConfigurationException}
     */
    public static boolean rejectsTwoProvidersForOneType() {
        SimpleEventBus eventBus = new SimpleEventBus();
        MessagingConfigurer configurer = MessagingConfigurer.create();
        configurer.componentRegistry(registry -> {
            // The parameter resolver that rejects the ambiguity is installed by the multi-tenancy
            // enhancer, so it is enabled here too. This guardrail is about provider ambiguity, so it
            // runs in memory regardless of whether the demo itself runs against Axon Server.
            MultiTenancyEnabled.enableMultiTenancyEnhancer(registry);
            registry.disableEnhancer(AxonServerConfigurationEnhancer.class)
                    .registerComponent(TenantProvider.class,
                                       config -> new DemoTenantProvider(TenantDescriptor.tenantWithId("sample")))
                    // Two providers for CourseStatsRepository make that handler parameter ambiguous.
                    .registerComponent(TenantComponentProvider.class, "courseStatsA",
                                       config -> UniversityModuleConfiguration.courseStatsProvider())
                    .registerComponent(TenantComponentProvider.class, "courseStatsB",
                                       config -> UniversityModuleConfiguration.courseStatsProvider());
        });
        // Register the projection through the normal event-processing path, so its handler inspection
        // resolves the ambiguous parameter exactly as it would in a real application.
        configurer.eventProcessing(eventProcessing -> eventProcessing.subscribing(
                subscribing -> subscribing
                        .defaults(defaults -> defaults.eventSource(eventBus))
                        .defaultProcessor("ambiguity-check",
                                          components -> components.autodetected(
                                                  "course-stats", config -> new CourseStatsProjection()))));
        AxonConfiguration configuration = configurer.build();
        try {
            configuration.start();
            logger.warn("Expected ambiguous providers to be rejected, but startup succeeded.");
            return false;
        } catch (RuntimeException e) {
            AxonConfigurationException ambiguity = ambiguityCause(e);
            if (ambiguity != null) {
                logger.info("Two providers for one component type rejected: {}", ambiguity.getMessage());
                return true;
            }
            throw e;
        } finally {
            configuration.shutdown();
        }
    }

    // The ambiguity surfaces as an AxonConfigurationException, wrapped in the lifecycle exception that
    // startup throws, so the cause chain is scanned rather than the top-level type.
    private static @Nullable AxonConfigurationException ambiguityCause(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof AxonConfigurationException axonConfigurationException) {
                return axonConfigurationException;
            }
        }
        return null;
    }
}
