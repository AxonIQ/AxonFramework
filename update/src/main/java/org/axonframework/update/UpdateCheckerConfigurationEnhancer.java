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

package org.axonframework.update;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.update.configuration.UsagePropertyProvider;
import org.axonframework.update.detection.TestEnvironmentDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.axonframework.common.configuration.ComponentDefinition.ofType;

/**
 * A {@link ConfigurationEnhancer} that registers the {@link UpdateChecker} component. This component is responsible for
 * reporting anonymous usage data to the Axoniq servers. It is registered during the external connections phase of the
 * lifecycle.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
@RegistrationScope
@Internal
public class UpdateCheckerConfigurationEnhancer implements ConfigurationEnhancer {

    private static final Logger logger = LoggerFactory.getLogger(UpdateCheckerConfigurationEnhancer.class);

    /**
     * The order of {@code this} enhancer compared to others, equal to {@link Integer#MAX_VALUE}.
     * <p>
     * Setting it to {@link Integer#MAX_VALUE} ensures this enhancer runs last, allowing users to override components
     * such as the {@link UpdateCheckerReporter}.
     */
    public static final int ENHANCER_ORDER = Integer.MAX_VALUE;

    @Override
    public void enhance(ComponentRegistry componentRegistry) {
        if (TestEnvironmentDetector.isTestEnvironment()) {
            logger.debug("Skipping Axoniq UpdateChecker as a testsuite environment was detected.");
            return;
        }
        componentRegistry
                .registerIfNotPresent(
                        ofType(UsagePropertyProvider.class).withBuilder(c -> UsagePropertyProvider.create())
                ).registerIfNotPresent(
                        ofType(UpdateCheckerHttpClient.class).withBuilder(c -> new UpdateCheckerHttpClient(c.getComponent(
                                UsagePropertyProvider.class)))
                )
                .registerIfNotPresent(
                        ofType(UpdateCheckerReporter.class).withBuilder(c -> new LoggingUpdateCheckerReporter())
                )
                .registerIfNotPresent(
                        ofType(UpdateChecker.class).withBuilder(c -> new UpdateChecker(
                                                           c.getComponent(UpdateCheckerHttpClient.class),
                                                           c.getComponent(UpdateCheckerReporter.class),
                                                           c.getComponent(UsagePropertyProvider.class)
                                                   ))
                                                   .onStart(Phase.EXTERNAL_CONNECTIONS, UpdateChecker::start)
                                                   .onShutdown(Phase.EXTERNAL_CONNECTIONS, UpdateChecker::stop)
                );
    }

    @Override
    public int order() {
        return ENHANCER_ORDER;
    }
}
