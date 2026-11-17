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
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Test class validating that the order in which {@link MessageHandlerConfigurer} receives event handling beans is the order
 * in which it hands them to the {@link ProcessorModuleFactory}.
 */
class MessageHandlerOrderingTest {

    @Test
    void handsEventHandlersToTheProcessorModuleFactoryInTheGivenOrder() {
        // given
        GenericApplicationContext context = new GenericApplicationContext();
        List<String> names = IntStream.range(0, 25).mapToObj(i -> "handler-" + i).toList();
        names.forEach(name -> context.registerBean(name, Handler.class));
        List<String> received = new ArrayList<>();
        context.registerBean(ProcessorModuleFactory.class, () -> handlers -> {
            handlers.forEach(handler -> received.add(handler.beanName()));
            return Set.of();
        });
        context.refresh();

        // when
        MessageHandlerConfigurer configurer = new MessageHandlerConfigurer(MessageHandlerConfigurer.Type.EVENT, names);
        configurer.setApplicationContext(context);
        configurer.enhance(mock(ComponentRegistry.class));

        // then
        assertThat(received).containsExactlyElementsOf(names);
        context.close();
    }

    static class Handler {

        @EventHandler
        void on(EventMessage event) {
            // Intentionally empty; the bean only needs to be an event handling component.
        }
    }
}
