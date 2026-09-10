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

import org.axonframework.common.ObjectUtils;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.annotation.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.OrderUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A {@link BeanDefinitionRegistryPostProcessor} implementation that detects beans with Axon Message handlers and
 * registers an {@link MessageHandlerConfigurer} to have these handlers registered in the Axon
 * {@link Configuration}.
 *
 * @author Allard Buijze
 * @since 4.6.0
 */
@Internal
public class MessageHandlerLookup implements BeanDefinitionRegistryPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(MessageHandlerLookup.class);

    /**
     * Returns a list of beans found in the given {@code register} that contain a handler for the given
     * {@code messageType}. The search will not consider prototype beans (or any other non-singleton or abstract bean
     * definitions).
     *
     * @param messageType The type of message to find handlers for.
     * @param registry    The registry to find these handlers in.
     * @return A list of bean names with message handlers.
     */
    public static List<String> messageHandlerBeans(Class<? extends Message> messageType,
                                                   ConfigurableListableBeanFactory registry) {
        return messageHandlerBeans(messageType, registry, false);
    }

    /**
     * Returns a list of beans found in the given {@code register} that contain a handler for the given
     * {@code messageType}. The search will only consider prototype beans (or any other non-singleton or abstract bean
     * definitions) when {@code includePrototypeBeans} is {@code true}.
     *
     * @param messageType The type of message to find handlers for.
     * @param registry    The registry to find these handlers in.
     * @param includePrototypeBeans Whether to include prototype beans.
     * @return A list of bean names with message handlers.
     */
    public static List<String> messageHandlerBeans(Class<? extends Message> messageType,
                                                   ConfigurableListableBeanFactory registry,
                                                   boolean includePrototypeBeans) {
        List<String> found = new ArrayList<>();
        for (String beanName : registry.getBeanDefinitionNames()) {
            BeanDefinition bd = registry.getBeanDefinition(beanName);

            if (bd.isAutowireCandidate()) {  // excludes unproxied variants of proxied beans
                if (includePrototypeBeans || (bd.isSingleton() && !bd.isAbstract())) {
                    Class<?> beanType = registry.getType(beanName);
                    if (beanType != null && hasMessageHandler(messageType, beanType)) {
                        found.add(beanName);
                    }
                }
            }
        }
        return found;
    }

    private static boolean hasMessageHandler(Class<? extends Message> messageType, Class<?> beanType) {
        for (Method m : ReflectionUtils.methodsOf(beanType)) {
            Optional<Map<String, Object>> attr = AnnotationUtils.findAnnotationAttributes(m, MessageHandler.class);
            if (attr.isPresent() && messageType.isAssignableFrom((Class<?>) attr.get().get("messageType"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!(beanFactory instanceof BeanDefinitionRegistry)) {
            logger.warn("Given bean factory is not a BeanDefinitionRegistry. Cannot auto-configure message handlers");
            return;
        }

        for (MessageHandlerConfigurer.Type value : MessageHandlerConfigurer.Type.values()) {
            String configurerBeanName = "MessageHandlerConfigurer$$Axon$$" + value.name();
            if (beanFactory.containsBeanDefinition(configurerBeanName)) {
                logger.info("Message handler configurer already available. Skipping configuration");
                break;
            }

            List<String> found = messageHandlerBeans(value.getMessageType(), beanFactory);
            if (!found.isEmpty()) {
                List<String> sortedFound = sortByOrder(found, beanFactory);
                AbstractBeanDefinition beanDefinition =
                        BeanDefinitionBuilder.genericBeanDefinition(MessageHandlerConfigurer.class)
                                             .addConstructorArgValue(value.name())
                                             .addConstructorArgValue(sortedFound)
                                             .getBeanDefinition();
                ((BeanDefinitionRegistry) beanFactory).registerBeanDefinition(configurerBeanName, beanDefinition);
            }
        }
    }

    /**
     * Sorts the given {@code found} bean references based on the value in the {@link Order} annotation on these beans.
     * <p>
     * This method uses the given {@code beanFactory} to find the value of the {@code Order} annotation. In absence of
     * this annotation, the order defaults to {@link Ordered#LOWEST_PRECEDENCE}.
     *
     * @param found       The bean references of components containing message handling functions.
     * @param beanFactory The bean factory used to find the {@link Order} annotation value.
     * @return A new {@link List} of sorted bean references, according to the value of the {@link Order} annotation
     * value on the beans.
     */
    private List<String> sortByOrder(List<String> found, ConfigurableListableBeanFactory beanFactory) {
        return found.stream()
                    .collect(Collectors.toMap(
                            beanRef -> beanRef,
                            beanRef -> OrderUtils.getOrder(
                                    ObjectUtils.getOrDefault(beanFactory.getType(beanRef), Object.class),
                                    Ordered.LOWEST_PRECEDENCE
                            )
                    ))
                    .entrySet()
                    .stream()
                    .sorted(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey)
                    .collect(Collectors.toList());
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        // No action required.
    }
}
