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

import org.axonframework.common.configuration.LazyInitializedModule;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Module;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MessageHandlerConfigurerTest {

    private final ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
    private final ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
    private final ComponentRegistry registry = mock(ComponentRegistry.class);

    @BeforeEach
    void setup() {
        when(applicationContext.getBeanFactory()).thenReturn(beanFactory);

        when(beanFactory.getBean(ArgumentMatchers.<String>any())).thenReturn(mock(MyHandler.class));
    }

    @Test
    void detectsAndRegistersCommandHandlersPerPackage() {
        Map<String, String> commandHandlers = new HashMap<>();
        commandHandlers.put("Handler1", "my.command.packaging.Handler1");
        commandHandlers.put("Handler2", "my.command.packaging.Handler2");
        commandHandlers.put("CustomHandler1", "my.command.packaging.custom.Handler1");
        commandHandlers.put("CustomHandler2", "my.command.packaging.custom.Handler2");
        commandHandlers.put("HandlerWithoutPackage", "HandlerWithoutPackage");
        commandHandlers.forEach((String handlerName, String fullQualifiedHandlerName) -> {
            var bdmock = beanDefinitionMock(fullQualifiedHandlerName);
            when(beanFactory.getBeanDefinition(eq(handlerName))).thenReturn(bdmock);
        });

        MessageHandlerConfigurer configurer = new MessageHandlerConfigurer(MessageHandlerConfigurer.Type.COMMAND,
                                                                           commandHandlers.keySet().stream().toList());
        configurer.setApplicationContext(applicationContext);
        configurer.enhance(registry);

        var moduleCaptor = ArgumentCaptor.forClass(Module.class);
        Mockito.verify(registry, times(3)).registerModule(moduleCaptor.capture());

        var registeredModules = moduleCaptor.getAllValues();
        assertThat(registeredModules).isNotNull();
        assertThat(registeredModules).hasSize(3);
        assertThat(registeredModules).allMatch(module -> module instanceof CommandHandlingModule);
        assertThat(registeredModules.stream().map(Module::name)).containsExactlyInAnyOrder(
                "CommandHandling[my.command.packaging]",
                "CommandHandling[my.command.packaging.custom]",
                "CommandHandling[default]"
        );
    }

    @Test
    void detectsAndRegistersEventHandlersPerPackage() {
        when(applicationContext.getBeansOfType(EventProcessorSettings.class)).thenReturn(Map.of(
                "my.event.packaging.custom",
                new EventProcessorSettings.SubscribingEventProcessorSettings() {
                    @Override
                    public String source() {
                        return "bean1";
                    }
                },
                "my.event.packaging",
                new EventProcessorSettings.PooledEventProcessorSettings() {
                    @Override
                    public int initialSegmentCount() {
                        return 7;
                    }

                    @Override
                    public long tokenClaimIntervalInMillis() {
                        return 5;
                    }

                    @Override
                    public int threadCount() {
                        return 3;
                    }

                    @Override
                    public int batchSize() {
                        return 19;
                    }

                    @Override
                    public String source() {
                        return "bean2";
                    }

                    @Override
                    public String tokenStore() {
                        return "bean3";
                    }
                }
        ));
        Map<String, String> eventHandlers = new HashMap<>();
        eventHandlers.put("Handler1", "my.event.packaging.Handler1");
        eventHandlers.put("Handler2", "my.event.packaging.Handler2");
        eventHandlers.put("CustomHandler1", "my.event.packaging.custom.Handler1");
        eventHandlers.put("CustomHandler2", "my.event.packaging.custom.Handler2");
        eventHandlers.put("HandlerWithoutPackage", "HandlerWithoutPackage");
        eventHandlers.forEach((String handlerName, String fullQualifiedHandlerName) -> {
                                  var bdmock = beanDefinitionMock(fullQualifiedHandlerName);
                                  when(beanFactory.getBeanDefinition(eq(handlerName))).thenReturn(bdmock);
                              }

        );
        MessageHandlerConfigurer configurer = new MessageHandlerConfigurer(MessageHandlerConfigurer.Type.EVENT,
                                                                           eventHandlers.keySet().stream().toList());
        configurer.setApplicationContext(applicationContext);
        configurer.enhance(registry);

        var moduleCaptor = ArgumentCaptor.forClass(Module.class);
        Mockito.verify(registry, times(3)).registerModule(moduleCaptor.capture());

        var registeredModules = moduleCaptor.getAllValues();
        assertThat(registeredModules).isNotNull();
        assertThat(registeredModules).hasSize(3);
        assertThat(registeredModules.get(0)).isInstanceOf(LazyInitializedModule.class);
        assertThat(registeredModules.get(1)).isInstanceOf(LazyInitializedModule.class);
        assertThat(registeredModules.get(2)).isInstanceOf(LazyInitializedModule.class);

        // since we use lazy initialization, there is no way to get the modules underneath
        // assertThat(registeredModules.get(0)).isInstanceOf(PooledStreamingEventProcessorModule.class);
        // assertThat(registeredModules.get(1)).isInstanceOf(SubscribingEventProcessorModule.class);
        // assertThat(registeredModules.get(2)).isInstanceOf(PooledStreamingEventProcessorModule.class);

        assertThat(registeredModules.stream().map(Module::name)).containsExactlyInAnyOrder(
                "Lazy[EventProcessor[my.event.packaging]]",
                "Lazy[EventProcessor[my.event.packaging.custom]]",
                "Lazy[EventProcessor[default]]"
        );
    }

    @Test
    void detectsAndRegistersQueryHandlersPerPackage() {
        Map<String, String> queryHandlers = new HashMap<>();
        queryHandlers.put("Handler1", "my.query.packaging.Handler1");
        queryHandlers.put("Handler2", "my.query.packaging.Handler2");
        queryHandlers.put("CustomHandler1", "my.query.packaging.custom.Handler1");
        queryHandlers.put("CustomHandler2", "my.query.packaging.custom.Handler2");
        queryHandlers.put("HandlerWithoutPackage", "HandlerWithoutPackage");
        queryHandlers.forEach((String handlerName, String fullQualifiedHandlerName) -> {
            var bdmock = beanDefinitionMock(fullQualifiedHandlerName);
            when(beanFactory.getBeanDefinition(eq(handlerName))).thenReturn(bdmock);
        });
        MessageHandlerConfigurer configurer = new MessageHandlerConfigurer(MessageHandlerConfigurer.Type.QUERY,
                                                                           queryHandlers.keySet().stream().toList());
        configurer.setApplicationContext(applicationContext);
        configurer.enhance(registry);

        var moduleCaptor = ArgumentCaptor.forClass(Module.class);
        Mockito.verify(registry, times(3)).registerModule(moduleCaptor.capture());

        var registeredModules = moduleCaptor.getAllValues();
        assertThat(registeredModules).isNotNull();
        assertThat(registeredModules).hasSize(3);
        assertThat(registeredModules).allMatch(module -> module instanceof QueryHandlingModule);
        assertThat(registeredModules.stream().map(Module::name)).containsExactlyInAnyOrder(
                "QueryHandling[my.query.packaging]",
                "QueryHandling[my.query.packaging.custom]",
                "QueryHandling[default]"
        );
    }

    private static AbstractBeanDefinition beanDefinitionMock(String fqcn) {
        var bdMock = mock(AbstractBeanDefinition.class);
        when(bdMock.getBeanClassName()).thenReturn(fqcn);
        return bdMock;
    }

    private static class MyHandler {

    }
}