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

package org.axonframework.examples.demo.multitenancy.shared.run;

import io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer;
import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.api.TenantProvider;
import io.axoniq.framework.messaging.multitenancy.axonserver.configuration.AxonServerMultiTenancyConfigurationDefaults;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.demo.multitenancy.shared.tenant.DemoTenantProvider;
import org.axonframework.examples.demo.multitenancy.shared.tenant.TenantComponents;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatisticsQueryHandler;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The demos' configuration-time guardrail: registering two {@link TenantComponentProvider}s for the
 * same component type makes a handler parameter of that type ambiguous, so the framework refuses to
 * resolve it. This is driven through the normal handling path, so it fails exactly where a real
 * application would: registering the {@link TenantStatisticsQueryHandler} and starting the configuration
 * triggers handler inspection, which cannot decide which provider feeds the handler's
 * {@code @TenantScoped CourseStatisticsStore} parameter.
 * <p>
 * It builds its own throwaway {@link MessagingConfigurer}, so both demos can show the same framework
 * guardrail regardless of how their own application is wired.
 */
public final class ProviderAmbiguityGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(ProviderAmbiguityGuardrail.class);

    private ProviderAmbiguityGuardrail() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Builds a throwaway configuration that registers the {@link TenantStatisticsQueryHandler} with two
     * providers for its {@code @TenantScoped CourseStatisticsStore} parameter, and checks that building and
     * starting it is rejected because the framework cannot know which provider to inject.
     *
     * @return {@code true} if the ambiguity was rejected with an {@link AxonConfigurationException}
     */
    public static boolean rejectsTwoProvidersForOneType() {
        MessagingConfigurer configurer = MessagingConfigurer.create();
        configurer.componentRegistry(registry -> {
            // The parameter resolver that rejects the ambiguity comes with multi-tenancy, which is active
            // as soon as the module is on the classpath. This guardrail is about provider ambiguity, so it
            // runs in memory regardless of whether the demo itself runs against Axon Server. Both
            // Axon Server enhancers are disabled: the multi-tenancy defaults would otherwise register a
            // multi-tenant command bus connector that needs an AxonServerConnectionManager we do not have.
            registry.disableEnhancer(AxonServerConfigurationEnhancer.class)
                    .disableEnhancer(AxonServerMultiTenancyConfigurationDefaults.class)
                    .registerComponent(TenantProvider.class,
                                       config -> new DemoTenantProvider(TenantDescriptor.tenantWithId("sample")))
                    // Two providers for CourseStatisticsStore make that handler parameter ambiguous.
                    .registerComponent(TenantComponentProvider.class, "courseStatisticsA",
                                       config -> TenantComponents.courseStatisticsProvider())
                    .registerComponent(TenantComponentProvider.class, "courseStatisticsB",
                                       config -> TenantComponents.courseStatisticsProvider());
        });
        // Register the query handler through the normal handling path, so its handler inspection resolves
        // the ambiguous parameter exactly as it would in a real application.
        configurer.registerQueryHandlingModule(
                QueryHandlingModule.named("ambiguity-check")
                                   .queryHandlers()
                                   .autodetectedQueryHandlingComponent(
                                           config -> new TenantStatisticsQueryHandler()));
        AxonConfiguration configuration = null;
        try {
            configuration = configurer.build();
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
            if (configuration != null) {
                configuration.shutdown();
            }
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
