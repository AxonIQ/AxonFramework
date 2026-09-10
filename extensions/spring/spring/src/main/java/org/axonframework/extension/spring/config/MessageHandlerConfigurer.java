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

import org.jspecify.annotations.Nullable;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.extension.spring.BeanDefinitionUtils;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;


import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * @author Allard Buijze
 * @since 4.6.0
 */
@Internal
@RegistrationScope("Don't copy this enhancer in order to avoid cyclic module build in Spring Boot.")
public class MessageHandlerConfigurer implements ConfigurationEnhancer, ApplicationContextAware {

    private final Type type;
    private final List<String> handlerBeansRefs;
    private @Nullable ApplicationContext applicationContext;

    /**
     * Registers the beans identified in given {@code beanRefs} as the given {@code type} of handler with the Axon
     * {@link Configuration}.
     *
     * @param type     The type of handler to register the beans as.
     * @param beanRefs A list of bean identifiers to register.
     */
    public MessageHandlerConfigurer(Type type, List<String> beanRefs) {
        this.type = type;
        this.handlerBeansRefs = beanRefs;
    }

    @Override
    public void enhance(ComponentRegistry registry) {
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
    }

    private void configureEventHandlers(ComponentRegistry registry) {
        if (handlerBeansRefs.isEmpty()) {
            // no action needed if there are no handler beans found
            return;
        }
        var beanFactory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        ProcessorModuleFactory processorModuleFactory = applicationContext.getBean(ProcessorModuleFactory.class);
        Set<EventProcessorDefinition.EventHandlerDescriptor> handlers =
                handlerBeansRefs.stream()
                                .map(name -> new SimpleEventHandlerDescriptor(name, beanFactory))
                                .collect(Collectors.toSet());
        for (EventProcessorModule processorModule : processorModuleFactory.buildProcessorModules(handlers)) {
            registry.registerModule(processorModule);
        }
    }

    private void configureQueryHandlers(ComponentRegistry registry) {
        groupNamedBeanDefinitionsByPackage().forEach((packageName, beanDefs) -> {
            var moduleName = "QueryHandling[" + packageName + "]";
            var queryHandlingModuleBuilder = QueryHandlingModule
                    .named(moduleName)
                    .queryHandlers();
            beanDefs.forEach(namedBeanDefinition -> queryHandlingModuleBuilder.autodetectedQueryHandlingComponent(
                    this.asComponent(namedBeanDefinition.name())
            ));
            registry.registerModule(queryHandlingModuleBuilder.build());
        });
    }

    private void configureCommandHandlers(ComponentRegistry registry) {
        groupNamedBeanDefinitionsByPackage().forEach((packageName, beanDefs) -> {
            var moduleName = "CommandHandling[" + packageName + "]";
            var commandHandlingModuleBuilder = CommandHandlingModule
                    .named(moduleName)
                    .commandHandlers();
            beanDefs.forEach(namedBeanDefinition -> commandHandlingModuleBuilder
                    .autodetectedCommandHandlingComponent(this.asComponent(namedBeanDefinition.name())));
            registry.registerModule(commandHandlingModuleBuilder.build());
        });
    }

    private ComponentBuilder<Object> asComponent(String beanName) {
        return (Configuration configuration) -> applicationContext.getBean(beanName);
    }

    private Map<String, List<NamedBeanDefinition>> groupNamedBeanDefinitionsByPackage() {

        // We need access to the BeanFactory, so cast to ConfigurableApplicationContext
        var beanFactory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        return handlerBeansRefs
                .stream()
                .map(name -> new NamedBeanDefinition(name, beanFactory.getBeanDefinition(name)))
                .collect(Collectors.groupingBy(nbd -> BeanDefinitionUtils.extractPackageName(nbd.definition)));
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * Named bean definition.
     *
     * @param name       name of the bean.
     * @param definition bean definition.
     */
    record NamedBeanDefinition(
            String name,
            BeanDefinition definition
    ) {

    }

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

    private record SimpleEventHandlerDescriptor(
            String beanName,
            ConfigurableListableBeanFactory beanFactory
    ) implements EventProcessorDefinition.EventHandlerDescriptor {

        @Override
        public BeanDefinition beanDefinition() {
            return beanFactory.getBeanDefinition(beanName);
        }

        @Override
        @Nullable
        public Class<?> beanType() {
            return beanFactory.getType(beanName);
        }

        @Override
        public Object resolveBean() {
            return beanFactory.getBean(beanName);
        }

        @Override
        public ComponentBuilder<Object> component() {
            return c -> resolveBean();
        }
    }
}
