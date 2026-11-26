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

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Module;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorModule;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
        var eventProcessorSettings = Map.of(
                "my.event.packaging.custom",
                (EventProcessorSettings.SubscribingEventProcessorSettings) () -> "bean1",
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
                    public @NonNull String tokenStore() {
                        return "bean3";
                    }
                },
                EventProcessorSettings.DEFAULT,
                (EventProcessorSettings.SubscribingEventProcessorSettings) () -> "mock"
        );
        when(applicationContext.getBean(ProcessorModuleFactory.class)).thenReturn(new DefaultProcessorModuleFactory(
                Collections.emptyList(), eventProcessorSettings));
        Map<String, String> eventHandlers = new HashMap<>();
        eventHandlers.put("Handler1", "my.event.packaging.Handler1");
        eventHandlers.put("Handler2", "my.event.packaging.Handler2");
        eventHandlers.put("CustomHandler1", "my.event.packaging.custom.Handler1");
        eventHandlers.put("CustomHandler2", "my.event.packaging.custom.Handler2");
        eventHandlers.put("HandlerWithoutPackage", "HandlerWithoutPackage");
        eventHandlers.forEach((String handlerName, String fullQualifiedHandlerName) -> {
                                  var bdmock = beanDefinitionMock(fullQualifiedHandlerName);
                                  when(beanFactory.getBeanDefinition(eq(handlerName))).thenReturn(bdmock);
                                  //noinspection rawtypes,unchecked
                                  when(beanFactory.getType(eq(handlerName))).thenReturn((Class) MyHandler.class);
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

        Map<String, List<Module>> modules = registeredModules.stream().collect(Collectors.groupingBy(Module::name));

        assertThat(modules.keySet()).containsExactlyInAnyOrder(
                "EventProcessor[my.event.packaging]",
                "EventProcessor[my.event.packaging.custom]",
                "EventProcessor[default]"
        );

        assertThat(modules.get("EventProcessor[default]").stream().map(Object::getClass))
                .isEqualTo(List.of(SubscribingEventProcessorModule.class));
        assertThat(modules.get("EventProcessor[my.event.packaging.custom]").stream().map(Object::getClass))
                .isEqualTo(List.of(SubscribingEventProcessorModule.class));
        assertThat(modules.get("EventProcessor[my.event.packaging]").stream().map(Object::getClass))
                .isEqualTo(List.of(PooledStreamingEventProcessorModule.class));
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

    @Test
    void handsEventHandlersToTheProcessorModuleFactoryInTheGivenOrder() {
        // given - enough handlers that a hash based ordering would practically never match the given order
        List<String> handlerNames = IntStream.range(0, 25).mapToObj(i -> "handler-" + i).toList();
        List<String> receivedNames = new ArrayList<>();
        when(applicationContext.getBean(ProcessorModuleFactory.class)).thenReturn(handlers -> {
            handlers.forEach(handler -> receivedNames.add(handler.beanName()));
            return Collections.emptySet();
        });
        MessageHandlerConfigurer configurer =
                new MessageHandlerConfigurer(MessageHandlerConfigurer.Type.EVENT, handlerNames);
        configurer.setApplicationContext(applicationContext);

        // when
        configurer.enhance(registry);

        // then
        assertThat(receivedNames).containsExactlyElementsOf(handlerNames);
    }

    private static AbstractBeanDefinition beanDefinitionMock(String fqcn) {
        var bdMock = mock(AbstractBeanDefinition.class);
        when(bdMock.getBeanClassName()).thenReturn(fqcn);
        return bdMock;
    }

    private static class MyHandler {

    }
}