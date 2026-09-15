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
package org.axonframework.messaging.core.timeout;

import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.core.configuration.MessagingConfigurationDefaults;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorCustomization;
import org.axonframework.messaging.queryhandling.QueryBus;

/**
 * A {@link ConfigurationEnhancer} that decorates the {@link UnitOfWorkFactory} used by the {@link CommandBus},
 * {@link QueryBus}, and every event processor, so every {@link UnitOfWork} it creates has a timeout, based on the
 * {@link TimeoutUnitOfWorkFactoryConfiguration} present in the {@link Configuration}.
 * <p>
 * Automatically registers a {@link TimeoutUnitOfWorkFactoryConfiguration#DEFAULT}
 * {@code TimeoutUnitOfWorkFactoryConfiguration} when none is present yet.
 *
 * @author Steven van Beelen
 * @see TimeoutUnitOfWorkFactoryConfiguration
 * @see TimeoutUnitOfWorkFactory
 * @since 5.4.0
 */
@RegistrationScope(
        "Register the decorators for the named CommandBus/QueryBus UnitOfWorkFactory components and the "
                + "EventProcessorCustomization decorator once at the root; do not re-invoke in child module registries. "
                + "CommandBus, QueryBus, and EventProcessorCustomization are all single, top-level components, never "
                + "re-resolved per module, so re-invoking this enhancer per module would register redundant, unused "
                + "decorators in registries that never consult them."
)
public class TimeoutUnitOfWorkFactoryConfigurationEnhancer implements ConfigurationEnhancer {

    /**
     * The order of {@code this} enhancer compared to others, equal to
     * {@link MessagingConfigurationDefaults#ENHANCER_ORDER} minus 10.
     * <p>
     * This value ensures the {@link EventProcessorCustomization} decorator set by this enhancer composes after any
     * default customization set by the {@link MessagingConfigurationDefaults}, still leaving some room for
     * customization by the user.
     */
    public static final int ENHANCER_ORDER = MessagingConfigurationDefaults.ENHANCER_ORDER - 10_000;

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.registerIfNotPresent(TimeoutUnitOfWorkFactoryConfiguration.class,
                                      c -> TimeoutUnitOfWorkFactoryConfiguration.DEFAULT);

        registry.registerDecorator(
                UnitOfWorkFactory.class,
                MessagingConfigurationDefaults.COMMAND_BUS_UNIT_OF_WORK_FACTORY_NAME,
                0,
                (config, name, delegate) -> wrapOrDelegate(
                        delegate,
                        "CommandBus",
                        config.getComponent(TimeoutUnitOfWorkFactoryConfiguration.class).getCommandBus()
                )
        );
        registry.registerDecorator(
                UnitOfWorkFactory.class,
                MessagingConfigurationDefaults.QUERY_BUS_UNIT_OF_WORK_FACTORY_NAME,
                0,
                (config, name, delegate) -> wrapOrDelegate(
                        delegate,
                        "QueryBus",
                        config.getComponent(TimeoutUnitOfWorkFactoryConfiguration.class).getQueryBus()
                )
        );
        registry.registerDecorator(
                EventProcessorCustomization.class,
                0,
                (config, componentName, existing) -> existing.andThen(
                        (axonConfig, processorConfig) -> processorConfig.unitOfWorkFactory(
                                wrapOrDelegate(
                                        processorConfig.unitOfWorkFactory(),
                                        "EventProcessor " + processorConfig.processorName(),
                                        axonConfig.getComponent(TimeoutUnitOfWorkFactoryConfiguration.class)
                                                  .eventProcessorSettings(processorConfig.processorName())
                                )
                        )
                )
        );
    }

    @Override
    public int order() {
        return ENHANCER_ORDER;
    }

    /**
     * Wraps the given {@code delegate} in a {@link TimeoutUnitOfWorkFactory} using the given {@code componentName} and
     * {@code settings}, or returns the {@code delegate} unchanged when {@code settings} are
     * {@link TaskTimeoutSettings#isDisabled() disabled}.
     *
     * @param delegate      the {@link UnitOfWorkFactory} to wrap
     * @param componentName the name of the component to be included in the logging
     * @param settings      the timeout settings to apply
     * @return a timeout-decorated {@link UnitOfWorkFactory}, or {@code delegate} unchanged when disabled
     */
    private static UnitOfWorkFactory wrapOrDelegate(
            UnitOfWorkFactory delegate, String componentName, TaskTimeoutSettings settings
    ) {
        return settings.isDisabled()
                ? delegate
                : new TimeoutUnitOfWorkFactory(delegate,
                                               componentName,
                                               settings.timeoutMs(),
                                               settings.warningThresholdMs(),
                                               settings.warningIntervalMs());
    }
}
