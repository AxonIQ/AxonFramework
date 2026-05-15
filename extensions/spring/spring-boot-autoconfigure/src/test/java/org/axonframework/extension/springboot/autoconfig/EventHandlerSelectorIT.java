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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.extension.spring.config.EventHandlerSelector;
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.axonframework.extension.springboot.fixture.event.namespace.inventory.InventoryEventHandler;
import org.axonframework.extension.springboot.fixture.event.namespace.orders.OrderEventHandler;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link EventHandlerSelector} use in an {@link EventProcessorDefinition}.
 * <p>
 * Contains tests for a custom with {@code EventHandlerSelector} as well as the out-of-the-box
 * {@link EventHandlerSelector#matchesNamespaceOnType(String)} usage through the
 * {@link EventProcessorDefinition#pooledStreamingMatching(String)} and
 * {@link EventProcessorDefinition#subscribingMatching(String)} methods. The latter tests assume usage of the
 * {@link org.axonframework.messaging.core.annotation.Namespace} annotation.
 *
 * @author Steven van Beelen
 */
class EventHandlerSelectorIT {

    private static final String ORDERS_NAMESPACE = "orders";
    private static final String ORDERS_PROCESSOR_NAME = "EventProcessor[" + ORDERS_NAMESPACE + "]";
    private static final String INVENTORY_NAMESPACE = "inventory";
    private static final String INVENTORY_PROCESSOR_NAME = "EventProcessor[" + INVENTORY_NAMESPACE + "]";

    @Nested
    @SpringBootTest(
            classes = {TestContext.class, ProcessorDefinitionWithSelectorContext.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE
    )
    class SelectorBasedMatchingTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void foundInventoryNamespaceOnType() {
            QualifiedName expectedEventHandlerName = new QualifiedName("inventory-events");

            AxonConfiguration axonConfiguration = context.getBean(AxonConfiguration.class);
            Configuration moduleConfig = axonConfiguration.getModuleConfiguration(INVENTORY_PROCESSOR_NAME)
                                                          .orElseThrow();

            assertThat(moduleConfig.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(
                    moduleConfig.getOptionalComponent(EventProcessor.class, INVENTORY_NAMESPACE)
            ).isPresent();
            assertThat(
                    moduleConfig.getOptionalComponent(SubscribingEventProcessorConfiguration.class)
            ).isPresent();
            Map<String, EventHandlingComponent> ehcs = moduleConfig.getComponents(EventHandlingComponent.class);
            assertThat(ehcs.size()).isEqualTo(1);
            EventHandlingComponent ehc = ehcs.values().stream().toList().getFirst();
            assertThat(ehc.supportedEvents()).contains(expectedEventHandlerName);
        }

        @Test
        void foundOrdersNamespaceOnPackageInfo() {
            QualifiedName expectedEventHandlerName = new QualifiedName("order-events");

            AxonConfiguration axonConfiguration = context.getBean(AxonConfiguration.class);
            Configuration moduleConfig = axonConfiguration.getModuleConfiguration(ORDERS_PROCESSOR_NAME)
                                                          .orElseThrow();

            assertThat(moduleConfig.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(
                    moduleConfig.getOptionalComponent(StreamingEventProcessor.class, ORDERS_NAMESPACE)
            ).isPresent();
            assertThat(
                    moduleConfig.getOptionalComponent(PooledStreamingEventProcessorConfiguration.class)
            ).isPresent();
            Map<String, EventHandlingComponent> ehcs = moduleConfig.getComponents(EventHandlingComponent.class);
            assertThat(ehcs.size()).isEqualTo(1);
            EventHandlingComponent ehc = ehcs.values().stream().toList().getFirst();
            assertThat(ehc.supportedEvents()).contains(expectedEventHandlerName);
        }
    }

    @Nested
    @SpringBootTest(
            classes = {TestContext.class, ProcessorDefinitionNameMatchingContext.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE
    )
    class NamespaceBasedMatchingTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void foundInventoryNamespaceOnType() {
            QualifiedName expectedEventHandlerName = new QualifiedName("inventory-events");

            AxonConfiguration axonConfiguration = context.getBean(AxonConfiguration.class);
            Configuration moduleConfig = axonConfiguration.getModuleConfiguration(INVENTORY_PROCESSOR_NAME)
                                                          .orElseThrow();

            assertThat(moduleConfig.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(
                    moduleConfig.getOptionalComponent(EventProcessor.class, INVENTORY_NAMESPACE)
            ).isPresent();
            assertThat(
                    moduleConfig.getOptionalComponent(SubscribingEventProcessorConfiguration.class)
            ).isPresent();
            Map<String, EventHandlingComponent> ehcs = moduleConfig.getComponents(EventHandlingComponent.class);
            assertThat(ehcs.size()).isEqualTo(1);
            EventHandlingComponent ehc = ehcs.values().stream().toList().getFirst();
            assertThat(ehc.supportedEvents()).contains(expectedEventHandlerName);
        }

        @Test
        void foundOrdersNamespaceOnPackageInfo() {
            QualifiedName expectedEventHandlerName = new QualifiedName("order-events");

            AxonConfiguration axonConfiguration = context.getBean(AxonConfiguration.class);
            Configuration moduleConfig = axonConfiguration.getModuleConfiguration(ORDERS_PROCESSOR_NAME)
                                                          .orElseThrow();

            assertThat(moduleConfig.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(
                    moduleConfig.getOptionalComponent(StreamingEventProcessor.class, ORDERS_NAMESPACE)
            ).isPresent();
            assertThat(
                    moduleConfig.getOptionalComponent(PooledStreamingEventProcessorConfiguration.class)
            ).isPresent();
            Map<String, EventHandlingComponent> ehcs = moduleConfig.getComponents(EventHandlingComponent.class);
            assertThat(ehcs.size()).isEqualTo(1);
            EventHandlingComponent ehc = ehcs.values().stream().toList().getFirst();
            assertThat(ehc.supportedEvents()).contains(expectedEventHandlerName);
        }
    }

    /**
     * Tests that {@link Namespace} annotations are resolved automatically by
     * {@link org.axonframework.extension.spring.config.DefaultProcessorModuleFactory} when no
     * {@link EventProcessorDefinition} beans are registered. The processor name is derived directly from the
     * {@code @Namespace} value on the handler type or its enclosing package, without requiring an explicit
     * {@link EventProcessorDefinition#pooledStreamingMatching(String)} or custom selector definition.
     */
    @Nested
    @SpringBootTest(
            classes = {TestContext.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE
    )
    class AutomaticNamespaceResolutionTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void handlerWithNamespaceOnTypeIsAssignedToNamespacedProcessorWithoutExplicitDefinition() {
            QualifiedName expectedEventHandlerName = new QualifiedName("inventory-events");

            AxonConfiguration axonConfiguration = context.getBean(AxonConfiguration.class);
            Configuration moduleConfig = axonConfiguration.getModuleConfiguration(INVENTORY_PROCESSOR_NAME)
                                                          .orElseThrow();

            assertThat(moduleConfig.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(
                    moduleConfig.getOptionalComponent(EventProcessor.class, INVENTORY_NAMESPACE)
            ).isPresent();
            Map<String, EventHandlingComponent> ehcs = moduleConfig.getComponents(EventHandlingComponent.class);
            assertThat(ehcs.size()).isEqualTo(1);
            EventHandlingComponent ehc = ehcs.values().stream().toList().getFirst();
            assertThat(ehc.supportedEvents()).contains(expectedEventHandlerName);
        }

        @Test
        void handlerWithNamespaceOnPackageIsAssignedToNamespacedProcessorWithoutExplicitDefinition() {
            QualifiedName expectedEventHandlerName = new QualifiedName("order-events");

            AxonConfiguration axonConfiguration = context.getBean(AxonConfiguration.class);
            Configuration moduleConfig = axonConfiguration.getModuleConfiguration(ORDERS_PROCESSOR_NAME)
                                                          .orElseThrow();

            assertThat(moduleConfig.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(
                    moduleConfig.getOptionalComponent(EventProcessor.class, ORDERS_NAMESPACE)
            ).isPresent();
            Map<String, EventHandlingComponent> ehcs = moduleConfig.getComponents(EventHandlingComponent.class);
            assertThat(ehcs.size()).isEqualTo(1);
            EventHandlingComponent ehc = ehcs.values().stream().toList().getFirst();
            assertThat(ehc.supportedEvents()).contains(expectedEventHandlerName);
        }
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    @ComponentScan(basePackageClasses = {OrderEventHandler.class, InventoryEventHandler.class})
    static class TestContext {

        @Bean
        public TokenStore tokenStore() {
            return new InMemoryTokenStore();
        }
    }

    @org.springframework.context.annotation.Configuration
    static class ProcessorDefinitionWithSelectorContext {

        @Bean
        public EventProcessorDefinition ordersProcessorDefinition() {
            return EventProcessorDefinition.pooledStreaming(ORDERS_NAMESPACE)
                                           .assigningHandlers(eventHandlerDescriptor -> {
                                               Class<?> handlerType = eventHandlerDescriptor.beanType();
                                               return handlerType != null
                                                       && OrderEventHandler.class.isAssignableFrom(handlerType);
                                           })
                                           .notCustomized();
        }

        @Bean
        public EventProcessorDefinition inventoryProcessorDefinition() {
            return EventProcessorDefinition.subscribing(INVENTORY_NAMESPACE)
                                           .assigningHandlers(eventHandlerDescriptor -> {
                                               Class<?> handlerType = eventHandlerDescriptor.beanType();
                                               return handlerType != null
                                                       && InventoryEventHandler.class.isAssignableFrom(handlerType);
                                           })
                                           .notCustomized();
        }
    }

    @org.springframework.context.annotation.Configuration
    static class ProcessorDefinitionNameMatchingContext {

        @Bean
        public EventProcessorDefinition ordersProcessorDefinition() {
            return EventProcessorDefinition.pooledStreamingMatching(ORDERS_NAMESPACE)
                                           .notCustomized();
        }

        @Bean
        public EventProcessorDefinition inventoryProcessorDefinition() {
            return EventProcessorDefinition.subscribingMatching(INVENTORY_NAMESPACE)
                                           .notCustomized();
        }
    }
}