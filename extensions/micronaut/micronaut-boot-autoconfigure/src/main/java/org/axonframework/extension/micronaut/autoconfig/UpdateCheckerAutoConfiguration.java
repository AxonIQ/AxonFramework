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

package org.axonframework.extension.micronaut.autoconfig;

import jakarta.annotation.Nonnull;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.extension.micronaut.UpdateCheckerProperties;
import org.axonframework.update.UpdateChecker;
import org.axonframework.update.UpdateCheckerConfigurationEnhancer;
import org.axonframework.update.UpdateCheckerHttpClient;
import org.axonframework.update.UpdateCheckerReporter;
import org.axonframework.update.configuration.UsagePropertyProvider;
import org.axonframework.update.detection.TestEnvironmentDetector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import static org.axonframework.common.configuration.ComponentDefinition.ofType;

/**
 * Autoconfiguration class constructing the {@link UsagePropertyProvider} that will end up in the
 * {@link org.axonframework.update.UpdateChecker}.
 * <p>
 * The {@code UsagePropertyProvider} will not be constructed if this configuration spins up in a test environment.
 *
 * @author Steven van Beelen
 * @since 4.12.0
 */
@AutoConfiguration
@EnableConfigurationProperties(UpdateCheckerProperties.class)
public class UpdateCheckerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @Conditional(NotTestEnvironmentCondition.class)
    public UsagePropertyProvider usagePropertyProvider(UpdateCheckerProperties properties) {
        return UsagePropertyProvider.create(properties);
    }

    /**
     * Bean creation method for a {@link ConfigurationEnhancer} for Spring-specific {@link UpdateChecker} components.
     * <p>
     * Although a duplicate of the {@link org.axonframework.update.UpdateCheckerConfigurationEnhancer}, this bean
     * creation method provides a Spring-specific mechanism to expect the <b>exact</b> {@link UsagePropertyProvider}
     * constructed in this file (based on type and name). This allows us to construct the a
     * {@link UpdateCheckerHttpClient} and {@link UpdateChecker} components with the right
     * {@code UsagePropertyProvider}, including any lifecycle operations. As such we ensure that we do not have to add
     * any component name in the {@code UpdateCheckerConfigurationEnhancer} to pick the Spring-specific
     * {@code UsagePropertyProvider} constructed by this autoconfiguration class.
     *
     * @param usagePropertyProvider The {@code UsagePropertyProvider} to attach to the {@link UpdateCheckerHttpClient}
     *                              and {@link UpdateChecker} constructed by this {@link ConfigurationEnhancer}.
     * @return A {@code ConfigurationEnhancer} constructing a {@link UpdateCheckerHttpClient} and {@link UpdateChecker}
     * based on the given {@code usagePropertyProvider}.
     */
    @Bean
    @Conditional(NotTestEnvironmentCondition.class)
    public ConfigurationEnhancer springUpdateCheckerConfigEnhancer(UsagePropertyProvider usagePropertyProvider) {
        return new ConfigurationEnhancer() {
            @Override
            public void enhance(@Nonnull ComponentRegistry registry) {
                registry.registerIfNotPresent(ofType(UpdateCheckerHttpClient.class).withBuilder(
                                c -> new UpdateCheckerHttpClient(usagePropertyProvider)
                        ))
                        .registerIfNotPresent(ofType(UpdateChecker.class)
                                                      .withBuilder(c -> new UpdateChecker(
                                                              c.getComponent(UpdateCheckerHttpClient.class),
                                                              c.getComponent(UpdateCheckerReporter.class),
                                                              usagePropertyProvider
                                                      ))
                                                      .onStart(Phase.EXTERNAL_CONNECTIONS, UpdateChecker::start)
                                                      .onShutdown(Phase.EXTERNAL_CONNECTIONS, UpdateChecker::stop));
            }

            @Override
            public int order() {
                return UpdateCheckerConfigurationEnhancer.ENHANCER_ORDER - 10;
            }
        };
    }

    static class NotTestEnvironmentCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !TestEnvironmentDetector.isTestEnvironment();
        }
    }
}