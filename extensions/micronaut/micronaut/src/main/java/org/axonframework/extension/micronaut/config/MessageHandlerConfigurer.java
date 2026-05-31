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

package org.axonframework.extension.micronaut.config;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanDefinitionReference;
import jakarta.annotation.Nonnull;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotNull;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.configuration.Module;
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer.AdditionalComponentPhase;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer.CompletePhase;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer.RequiredComponentPhase;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.axonframework.extension.micronaut.config.EventProcessorSettings.PooledEventProcessorSettings;
import org.axonframework.extension.micronaut.config.EventProcessorSettings.SubscribingEventProcessorSettings;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An implementation of a {@link ConfigurationEnhancer} that will register a list of beans as handlers for a specific
 * type of message.
 * <p>
 * The beans will be lazily resolved to avoid circular dependencies if any these beans relies on the Axon
 * {@link Configuration} to be available in the Application Context.
 * <p>
 * Typically, an application context would have an instance of this class registered for each type of message to
 * register.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@Internal
@Singleton
public class MessageHandlerConfigurer implements ConfigurationEnhancer {

    private final Map<Type, List<BeanDefinition<?>>> handlerBeanDefinitions = new ConcurrentHashMap<>();
    private final BeanContext beanContext;

    /**
     * Registers the beans identified in given {@code beanRefs} as the given {@code type} of handler with the Axon
     * {@link Configuration}.
     *
     * @param beanContext The bean context
     */
    public MessageHandlerConfigurer(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public void enhance(@Nonnull ComponentRegistry registry) {
        handlerBeanDefinitions.forEach((type, beanDefinitionReferences) -> {
            switch (type) {
                case EVENT:
                    configureEventHandlers(registry);
                    break;
                case QUERY:
                    configureQueryHandlers(registry);
                    break;
                case COMMAND:
                    configureCommandHandlers(registry);
                    break;
            }
        });
    }

    public void register(@NotNull Type type, @NotNull BeanDefinition<?> beanDefinition) {
        handlerBeanDefinitions.computeIfAbsent(type, p -> new CopyOnWriteArrayList<>())
                              .add(beanDefinition);
    }

    private void configureEventHandlers(ComponentRegistry registry) {

//        groupNamedBeanDefinitionsByPackage().forEach((packageName, beanDefs) -> {
//
//            Function<RequiredComponentPhase, CompletePhase> componentRegistration = (RequiredComponentPhase phase) -> {
//                AdditionalComponentPhase resultOfRegistration = null;
//                for (BeanDefinitionReference<?> namedBeanDefinition : beanDefs) {
//                    resultOfRegistration = phase.autodetected(this.createComponentBuilder(namedBeanDefinition));
//                }
//                return resultOfRegistration;
//            };
//
//            var processorName = "EventProcessor[" + packageName + "]";
//
//            Function<Configuration, ModuleBuilder<? extends Module>> moduleBuilder = (configuration) -> {
//
//                var allSettings = configuration.getComponent(EventProcessorSettings.MapWrapper.class).settings();
//                var settings = Optional.ofNullable(allSettings.get(packageName))
//                                       .orElseGet(() -> allSettings.get(EventProcessorSettings.DEFAULT));
//                return switch (settings.processorMode()) {
//                    case POOLED -> {
//                        var moduleSettings = (PooledEventProcessorSettings) settings;
//                        yield EventProcessorModule
//                                .pooledStreaming(processorName)
//                                .eventHandlingComponents(componentRegistration)
//                                .customized(MicronautCustomizations.pooledStreamingCustomizations(
//                                                                           packageName,
//                                                                           moduleSettings
//                                                                   )
//                                                                   .andThen(c -> c.unitOfWorkFactory(configuration.getComponent(
//                                                                           UnitOfWorkFactory.class))));
//                    }
//                    case SUBSCRIBING -> {
//                        var moduleSettings = (SubscribingEventProcessorSettings) settings;
//                        yield EventProcessorModule
//                                .subscribing(processorName)
//                                .eventHandlingComponents(componentRegistration)
//                                .customized(MicronautCustomizations.subscribingCustomizations(
//                                                                           packageName,
//                                                                           moduleSettings
//                                                                   )
//                                                                   .andThen(c -> c.unitOfWorkFactory(configuration.getComponent(
//                                                                           UnitOfWorkFactory.class))));
//                    }
//                };
//            };
//            registry.registerModule(
//                    new LazyInitializedModule<>("Lazy[" + processorName + "]", moduleBuilder)
//            );
//        });
    }

    private void configureQueryHandlers(ComponentRegistry registry) {
     /*   groupNamedBeanDefinitionsByPackage().forEach((packageName, beanDefs) -> {
            var moduleName = "QueryHandling[" + packageName + "]";
            var queryHandlingModuleBuilder = QueryHandlingModule
                    .named(moduleName)
                    .queryHandlers();
            beanDefs.forEach(namedBeanDefinition -> queryHandlingModuleBuilder.annotatedQueryHandlingComponent(
                    this.createComponentBuilder(namedBeanDefinition)
            ));
            registry.registerModule(queryHandlingModuleBuilder.build());
        });*/
    }

    private void configureCommandHandlers(ComponentRegistry registry) {
    /*    groupNamedBeanDefinitionsByPackage().forEach((packageName, beanDefs) -> {
            var moduleName = "CommandHandling[" + packageName + "]";
            var commandHandlingModuleBuilder = CommandHandlingModule
                    .named(moduleName)
                    .commandHandlers();
            beanDefs.forEach(namedBeanDefinition -> {
                commandHandlingModuleBuilder
                        .annotatedCommandHandlingComponent(this.createComponentBuilder(namedBeanDefinition));
            });
            registry.registerModule(commandHandlingModuleBuilder.build());
        });*/
    }

    private ComponentBuilder<Object> createComponentBuilder(
            @Nonnull BeanDefinitionReference<?> beanDefinitionReference) {
        return (Configuration configuration) -> beanContext.getBean(beanDefinitionReference.load());
    }

//    private Map<String, List<BeanDefinitionReference<?>>> groupNamedBeanDefinitionsByPackage() {
//        return handlerBeanDefinitions
//                .stream()
//                .collect(Collectors.groupingBy(
//                                 hbdr ->
//                                         (hbdr.getBeanDefinitionName() != null && hbdr.getBeanDefinitionName().contains(
//                                                 "."))
//                                                 ? hbdr.getBeanDefinitionName()
//                                                       .substring(0, hbdr.getBeanDefinitionName().lastIndexOf('.'))
//                                                 : "default"
//                         )
//                );
//    }

    /**
     * Enumeration defining the auto configurable message handler types.
     */
    public enum Type {

        /**
         * Lists the message handler for {@link CommandMessage CommandMessages}.
         */
        COMMAND(CommandMessage.class),
        /**
         * Lists the message handler for {@link EventMessage EventMessages}.
         */
        EVENT(EventMessage.class),
        /**
         * Lists the message handler for {@link QueryMessage QueryMessages}.
         */
        QUERY(QueryMessage.class);

        private final Class<? extends Message> messageType;

        // Suppressed to allow instantiation of enumeration.
        @SuppressWarnings({"rawtypes", "unchecked"})
        Type(Class<? extends Message> messageType) {
            this.messageType = messageType;
        }

        /**
         * Returns the supported {@link Message} implementation.
         *
         * @return The supported {@link Message} implementation.
         */
        public Class<? extends Message> getMessageType() {
            return messageType;
        }
    }
}
