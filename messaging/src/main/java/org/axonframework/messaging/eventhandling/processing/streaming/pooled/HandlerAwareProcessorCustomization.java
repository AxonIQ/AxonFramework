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

package org.axonframework.messaging.eventhandling.processing.streaming.pooled;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SegmentChangeListener;

import java.util.List;
import java.util.Objects;

/**
 * A {@link PooledStreamingEventProcessorConfiguration} customization invoked when a
 * {@link PooledStreamingEventProcessor} is being built, granting access to that specific processor's resolved
 * {@link EventHandlingComponent} list in addition to its configuration.
 * <p>
 * Unlike the unnamed configuration customizer registered through
 * {@link PooledStreamingEventProcessorModule#customized}, this hook fires <em>after</em> the handler list has
 * been resolved, so it can derive configuration from the handlers themselves (for example, registering a
 * {@link SegmentChangeListener} that observes capabilities declared by individual handlers). The hook
 * customizes the <strong>processor configuration</strong>, not the handlers &mdash; to wrap or decorate
 * handlers, use the existing {@code EventHandlingComponent}-typed decorator registration.
 * <p>
 * Customizations are <em>discovered</em> from the root {@link Configuration} via
 * {@code getComponents(HandlerAwareProcessorCustomization.class).values()}. Register them at the top-level
 * configurer (e.g. through a {@code ConfigurationEnhancer}); the module walks up to the root configuration so
 * the lookup is independent of where the module itself sits in the configuration tree.
 * <p>
 * Multiple instances may co-exist; each receives the configuration returned by the previous customization
 * before the {@link PooledStreamingEventProcessor} is constructed. The order in which customizations are
 * applied is not part of the contract &mdash; implementations must be designed to be applied independently
 * of one another. To impose a deterministic order, compose customizations with {@link #andThen} and register
 * the composed instance as a single component.
 * <p>
 * Implementations typically mutate the supplied {@code processorConfig} in place and return the same instance
 * (the common "mutate-and-return-this" pattern):
 * <pre>{@code
 * (config, name, handlers, processorConfig) -> {
 *     processorConfig.addSegmentChangeListener(deriveListener(handlers));
 *     return processorConfig;
 * }
 * }</pre>
 * Returning a <em>different</em> {@code PooledStreamingEventProcessorConfiguration} instance is permitted but
 * affects only the processor under construction; the configuration registered as a component in the wider
 * {@link Configuration} is unchanged.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
@FunctionalInterface
public interface HandlerAwareProcessorCustomization {

    /**
     * Customizes the given {@code processorConfig} with knowledge of the {@code handlers} assigned to the
     * processor identified by {@code processorName}.
     *
     * @param config          the surrounding {@link Configuration}
     * @param processorName   the name of the processor under construction
     * @param handlers        the {@link EventHandlingComponent}s assigned to this processor, in registration
     *                        order, with all configuration-level decorators already applied
     * @param processorConfig the current {@link PooledStreamingEventProcessorConfiguration}, possibly already
     *                        modified by earlier customizations
     * @return the {@link PooledStreamingEventProcessorConfiguration} to use for processor construction &mdash;
     *         typically the same {@code processorConfig} instance after in-place mutation
     */
    PooledStreamingEventProcessorConfiguration customize(Configuration config,
                                                        String processorName,
                                                        List<EventHandlingComponent> handlers,
                                                        PooledStreamingEventProcessorConfiguration processorConfig);

    /**
     * Returns a customization that applies {@code this} first and {@code next} second, threading the
     * configuration returned by {@code this} into {@code next}.
     *
     * @param next the customization to apply after {@code this}
     * @return a composed customization
     */
    default HandlerAwareProcessorCustomization andThen(HandlerAwareProcessorCustomization next) {
        Objects.requireNonNull(next, "Next customization may not be null");
        return (config, processorName, handlers, processorConfig) -> {
            PooledStreamingEventProcessorConfiguration after =
                    this.customize(config, processorName, handlers, processorConfig);
            return next.customize(config, processorName, handlers, after);
        };
    }

    /**
     * Returns a no-op customization that returns the supplied {@code processorConfig} unchanged.
     *
     * @return a no-op customization
     */
    static HandlerAwareProcessorCustomization noOp() {
        return (config, processorName, handlers, processorConfig) -> processorConfig;
    }
}
