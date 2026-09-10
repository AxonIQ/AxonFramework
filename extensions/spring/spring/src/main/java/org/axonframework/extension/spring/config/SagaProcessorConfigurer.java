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

package org.axonframework.extension.spring.config;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.spring.stereotype.Saga;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@link ConfigurationEnhancer} that builds one dedicated {@link EventProcessorModule} per resolved processor name
 * among the {@link Saga @Saga} beans in the application.
 * <p>
 * Hands the {@link SpringSagaDescriptor} of every {@code @Saga} bean to a {@link DefaultProcessorModuleFactory} built
 * just for this call, so a Saga's processor name, settings, and matching {@link EventProcessorDefinition} resolve the
 * same way a regular event handler's do -- without reimplementing that resolution here. The factory is entirely
 * separate from the one the plain event handling beans go through: two independent
 * {@link ComponentRegistry#registerModule(org.axonframework.common.configuration.Module) registerModule} calls, one per
 * factory, so a Saga is never silently grouped onto a processor a regular event handler also claims. If both resolve
 * the same processor name, registering the second module fails with a
 * {@link org.axonframework.common.configuration.DuplicateModuleRegistrationException}, rather than the two being
 * merged.
 * <p>
 * A Saga's processor name resolves the same way as any other handler's -- a matching {@link EventProcessorDefinition}
 * selector, then a {@link org.axonframework.messaging.core.annotation.Namespace} on its type -- except the
 * package-name fallback {@link DefaultProcessorModuleFactory} otherwise applies is replaced, for a Saga without a
 * {@code Namespace}, by a definition synthesized from {@link SpringSagaDescriptor#preferredProcessorName()}: the name
 * Axon Framework 4 gave it, which keeps a migrating application's token store row claimable. That synthesized
 * definition is never customized, so an application's own {@code EventProcessorDefinition} bean or
 * {@code axon.eventhandling.processors} entry for the same name still applies on top of it. Several Sagas whose
 * processor name resolves to the same value -- e.g. because they share a {@code Namespace} -- are grouped onto that
 * one processor by the same {@link DefaultProcessorModuleFactory} logic that groups regular handlers, matching how
 * Axon Framework 4 let several Sagas share a {@code @ProcessingGroup}.
 * <p>
 * Registered as a bean by {@code SagaAutoConfiguration}; an application never creates this itself.
 * <p>
 * Not copied into a module's own nested {@link ComponentRegistry}: a {@link org.axonframework.common.configuration.Module}
 * is built through the same {@link ConfigurationEnhancer} invocation this class registers modules from, so copying it
 * down would re-run this enhancer for every processor module it builds, which builds another full set of processor
 * modules on that module's own registry, which builds another set on each of those, without ever terminating.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Internal
@RegistrationScope("Don't copy this enhancer, or building one Saga's processor module recurses into building "
        + "another full set of Saga processor modules on that module's own registry, forever.")
public class SagaProcessorConfigurer implements ConfigurationEnhancer, ApplicationContextAware {

    private @Nullable ApplicationContext applicationContext;

    @Override
    public void enhance(ComponentRegistry registry) {
        var context = Objects.requireNonNull(applicationContext);
        Collection<SpringSagaDescriptor> sagas = context.getBeansOfType(SpringSagaDescriptor.class).values();
        if (sagas.isEmpty()) {
            return;
        }

        Map<String, EventProcessorSettings> settings =
                context.getBean(EventProcessorSettings.MapWrapper.class).settings();

        // Real definitions first: definitionFor(...) resolves settings by name and returns the first match, so an
        // application's own EventProcessorDefinition for a Saga's preferred name is what applies, not this fallback.
        List<EventProcessorDefinition> definitions = new ArrayList<>();
        context.getBeanProvider(EventProcessorDefinition.class).orderedStream().forEach(definitions::add);
        definitions.addAll(preferredNameDefinitions(sagas, settings));

        var sagaProcessorFactory = new DefaultProcessorModuleFactory(
                definitions,
                settings,
                List.of(), // no DLQ for Sagas -- Axon Framework 4 never supported dead-lettering for them
                sagas.iterator().next().pooledStreamingDefaults()
        );

        Set<EventProcessorDefinition.EventHandlerDescriptor> descriptors =
                sagas.stream().map(SagaHandlerDescriptor::new).collect(Collectors.toCollection(HashSet::new));
        for (EventProcessorModule module : sagaProcessorFactory.buildProcessorModules(descriptors)) {
            registry.registerModule(module);
        }
    }

    /**
     * Synthesizes a naming-only {@link EventProcessorDefinition} for every Saga without an explicit
     * {@link org.axonframework.messaging.core.annotation.Namespace}, selecting it by bean name into
     * {@link SpringSagaDescriptor#preferredProcessorName()} rather than letting it fall through to
     * {@link DefaultProcessorModuleFactory}'s package-name default.
     * <p>
     * {@code notCustomized()}: these definitions exist purely to name the processor. A real
     * {@link EventProcessorDefinition} bean or {@code axon.eventhandling.processors} entry an application declares for
     * that same name still takes effect -- it is applied by {@link DefaultProcessorModuleFactory} the same way
     * regardless of which definition supplied the name.
     * <p>
     * Its mode -- {@link EventProcessorDefinition#pooledStreaming(String) pooled} or
     * {@link EventProcessorDefinition#subscribing(String) subscribing} -- is read from the same {@code settings} an
     * unmatched processor would otherwise use, so synthesizing this definition never overrides a mode an application
     * configured through {@code axon.eventhandling.processors}: a matching {@link EventProcessorDefinition}'s mode
     * always wins over settings once one exists, so this one has to already agree with settings before it exists.
     * <p>
     * A Saga carrying a {@code Namespace} is left out here entirely: {@link DefaultProcessorModuleFactory} already
     * resolves that name on its own, the same way it does for a regular handler, so several Sagas sharing one
     * {@code Namespace} are grouped onto that processor without any help from this method.
     *
     * @param sagas    every {@code @Saga} bean's descriptor
     * @param settings the settings map a processor without a matching definition would otherwise resolve its mode from
     * @return a naming-only definition for each Saga without a {@code Namespace}
     */
    private List<EventProcessorDefinition> preferredNameDefinitions(
            Collection<SpringSagaDescriptor> sagas,
            Map<String, EventProcessorSettings> settings
    ) {
        return sagas.stream()
                    .filter(saga -> EventProcessorModuleAssembler.resolveNamespace(saga.beanType()).isEmpty())
                    .map(saga -> preferredNameDefinition(saga, settings))
                    .toList();
    }

    private EventProcessorDefinition preferredNameDefinition(
            SpringSagaDescriptor saga,
            Map<String, EventProcessorSettings> settings
    ) {
        String processorName = saga.preferredProcessorName().orElseThrow();
        EventProcessorSettings resolvedSettings = Optional.ofNullable(settings.get(processorName))
                                                           .orElseGet(() -> settings.get(EventProcessorSettings.DEFAULT));
        EventHandlerSelector selectsThisSaga = descriptor -> descriptor.beanName().equals(saga.beanName());
        return switch (resolvedSettings.processorMode()) {
            case POOLED -> EventProcessorDefinition.pooledStreaming(processorName)
                                                   .assigningHandlers(selectsThisSaga)
                                                   .notCustomized();
            case SUBSCRIBING -> EventProcessorDefinition.subscribing(processorName)
                                                        .assigningHandlers(selectsThisSaga)
                                                        .notCustomized();
        };
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * Adapts a {@link SpringSagaDescriptor} to an {@link EventProcessorDefinition.EventHandlerDescriptor} for
     * {@link DefaultProcessorModuleFactory}, without making {@link SpringSagaDescriptor} itself one.
     * <p>
     * {@link MessageHandlerConfigurer} auto-discovers every Spring bean assignable to
     * {@link EventProcessorDefinition.EventHandlerDescriptor} for the plain event handling pipeline; if
     * {@link SpringSagaDescriptor} implemented the interface directly, every {@code @Saga} bean would be built into a
     * processor module twice -- once there, once here -- and the second {@code registerModule} call would fail with a
     * {@link org.axonframework.common.configuration.DuplicateModuleRegistrationException}. This adapter exists only for
     * the duration of this call, never registered as a bean, so it is never discovered by that scan.
     */
    private record SagaHandlerDescriptor(SpringSagaDescriptor saga) implements EventProcessorDefinition.EventHandlerDescriptor {

        @Override
        public String beanName() {
            return saga.beanName();
        }

        @Override
        public BeanDefinition beanDefinition() {
            return saga.beanDefinition();
        }

        @Override
        public Class<?> beanType() {
            return saga.beanType();
        }

        @Override
        public Object resolveBean() {
            return saga.resolveBean();
        }

        @Override
        public ComponentBuilder<Object> component() {
            return saga.component();
        }
    }
}
