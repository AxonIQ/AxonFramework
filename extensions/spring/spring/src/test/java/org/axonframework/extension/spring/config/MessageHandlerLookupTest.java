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

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating which beans {@link MessageHandlerLookup} hands to the event handling configuration.
 *
 * @author Mateusz Nowak
 */
class MessageHandlerLookupTest {

    /**
     * A prototype-scoped bean carrying {@link EventHandler @EventHandler} methods is detected as an event handling
     * component, but the lookup deliberately excludes it unless prototype beans are explicitly requested. This guards
     * against wiring a bean that a higher-level component (such as an Axon Framework 4 Saga) manages itself, in
     * addition to that component. The scope check is the whole guard.
     */
    @Nested
    class PrototypeScopedHandlers {

        @Test
        void excludesAPrototypeScopedHandler() {
            // given - the same handler type as a prototype and as a singleton bean
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            handlerBean(beanFactory, "prototypeHandler", BeanDefinition.SCOPE_PROTOTYPE);
            handlerBean(beanFactory, "singletonHandler", BeanDefinition.SCOPE_SINGLETON);

            // when
            List<String> found = MessageHandlerLookup.messageHandlerBeans(EventMessage.class, beanFactory, false);

            // then - the singleton proves the handler is detected; only the scope keeps the prototype out
            assertThat(found).containsExactly("singletonHandler");
        }

        @Test
        void includesAPrototypeScopedHandlerWhenPrototypeBeansAreRequested() {
            // given
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            handlerBean(beanFactory, "prototypeHandler", BeanDefinition.SCOPE_PROTOTYPE);

            // when
            List<String> found = MessageHandlerLookup.messageHandlerBeans(EventMessage.class, beanFactory, true);

            // then
            assertThat(found).containsExactly("prototypeHandler");
        }
    }

    @Nested
    class RepeatedHandlerAnnotations {

        @Test
        void detectsABeanWhoseOnlyHandlersRepeatTheirAnnotation() {
            // given
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            beanFactory.registerBeanDefinition(
                    "repeatedHandler",
                    BeanDefinitionBuilder.genericBeanDefinition(RepeatedEventHandler.class).getBeanDefinition()
            );

            // when
            List<String> found = MessageHandlerLookup.messageHandlerBeans(EventMessage.class, beanFactory, false);

            // then
            assertThat(found).containsExactly("repeatedHandler");
        }
    }

    /**
     * Event handling components within a processor are invoked in the order the lookup hands them over, so the lookup
     * must sort by {@link Order @Order} and keep registration order for beans of equal order.
     */
    @Nested
    class Ordering {

        @Test
        void sortsHandlersByOrderAnnotation() {
            // given
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            handlerBean(beanFactory, "last", LastHandler.class);
            handlerBean(beanFactory, "unordered", SimpleEventHandler.class);
            handlerBean(beanFactory, "second", SecondHandler.class);
            handlerBean(beanFactory, "first", FirstHandler.class);

            // when
            new MessageHandlerLookup().postProcessBeanFactory(beanFactory);

            // then
            assertThat(eventHandlerBeanNames(beanFactory)).containsExactly("first", "second", "last", "unordered");
        }

        @Test
        void retainsRegistrationOrderForHandlersWithEqualOrder() {
            // given - enough beans that a hash based ordering would practically never match registration order
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            List<String> registered = IntStream.range(0, 25).mapToObj(i -> "handler-" + i).toList();
            registered.forEach(name -> handlerBean(beanFactory, name, SimpleEventHandler.class));

            // when
            new MessageHandlerLookup().postProcessBeanFactory(beanFactory);

            // then
            assertThat(eventHandlerBeanNames(beanFactory)).containsExactlyElementsOf(registered);
        }

        @SuppressWarnings({"unchecked", "DataFlowIssue"})
        private List<String> eventHandlerBeanNames(DefaultListableBeanFactory beanFactory) {
            BeanDefinition configurer = beanFactory.getBeanDefinition("MessageHandlerConfigurer$$Axon$$EVENT");
            return (List<String>) configurer.getConstructorArgumentValues()
                                            .getIndexedArgumentValue(1, null)
                                            .getValue();
        }

        private void handlerBean(DefaultListableBeanFactory beanFactory, String beanName, Class<?> type) {
            beanFactory.registerBeanDefinition(
                    beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(type).getBeanDefinition()
            );
        }
    }

    private static void handlerBean(DefaultListableBeanFactory beanFactory, String beanName, String scope) {
        beanFactory.registerBeanDefinition(beanName,
                                           BeanDefinitionBuilder.genericBeanDefinition(SimpleEventHandler.class)
                                                                .setScope(scope)
                                                                .getBeanDefinition());
    }

    static class SimpleEventHandler {

        @EventHandler
        void on(SomethingHappened event) {
            // Intentionally empty; the bean only needs a handler for the lookup to consider it.
        }
    }

    static class RepeatedEventHandler {

        @EventHandler(namespace = "current", name = "SomethingHappened")
        @EventHandler(namespace = "legacy", name = "SomethingHappened")
        void on(SomethingHappened event) {
            // Intentionally empty; the bean only needs a handler for the lookup to consider it.
        }
    }

    @Order(1)
    static class FirstHandler extends SimpleEventHandler {

    }

    @Order(2)
    static class SecondHandler extends SimpleEventHandler {

    }

    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    static class LastHandler extends SimpleEventHandler {

    }

    record SomethingHappened(String id) {

    }
}
