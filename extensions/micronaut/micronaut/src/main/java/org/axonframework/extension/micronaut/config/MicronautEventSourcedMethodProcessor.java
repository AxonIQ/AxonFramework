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
import io.micronaut.context.annotation.Context;
import io.micronaut.context.processor.BeanDefinitionProcessor;
import io.micronaut.inject.BeanDefinition;
import jakarta.annotation.PreDestroy;
import org.axonframework.common.annotation.Internal;
import org.axonframework.extension.micronaut.stereotype.EventSourced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link BeanDefinitionRegistryPostProcessor} implementation that scans for Aggregate types and registers a
 * {@link MicronautEventSourcedEntityConfigurer configurer} for each Aggregate found.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@Internal @Context public class MicronautEventSourcedMethodProcessor implements BeanDefinitionProcessor<EventSourced> {

    private static final Logger logger = LoggerFactory.getLogger(MicronautEventSourcedMethodProcessor.class);

    private static final String ID_TYPE_CLASS = "idType";

    Map<BeanDefinition<?>, Collection<BeanDefinition<?>>> hierarchy = new HashMap<>();
    private final BeanContext beanContext;

    public MicronautEventSourcedMethodProcessor(BeanContext beanContext) {
        this.beanContext = beanContext;
    }


    private static <E> Class<? super E> topAnnotatedEntityType(Class<E> type) {
        Class<? super E> top = type;
        Class<? super E> topAnnotated = top;
        while (!Object.class.equals(top) && !Object.class.equals(top.getSuperclass())) {
            top = top.getSuperclass();
            if (top.isAnnotationPresent(EventSourced.class)) {
                topAnnotated = top;
            }
        }
        return topAnnotated;
    }

    /**
     * When there are subtypes present we are dealing with a polymorphic entity. In those cases the root class should be
     * {@code abstract}, causing Spring to <b>not</b> add the {@link EventSourced} class to the
     * {@link org.springframework.context.ApplicationContext}. Due to this, there is no {@link BeanDefinition} present
     * to begin with. As such, we skip the {@link BeanDefinition#isPrototype()} check whenever there are subtypes. If
     * there are <b>no</b> subtypes, the {@code entityPrototype} is expected to reference a prototype
     * {@code BeanDefinition}.
     *
     * @param entitySubtypes The collection of subtypes found for the given {@code entityPrototype}. May be empty.
     * @param beanDefinition The bean factory to retrieve the prototype {@link BeanDefinition} from, based on the given
     *                       {@code entityPrototype}. {@link BeanDefinition#isPrototype() prototype bean}.
     * @return Whether there are subtypes present or whether the {@code entityPrototype} is a prototype
     * {@link BeanDefinition}.
     */
    // TODO: check if abstract top level classes have a bean definition
    private static boolean hasSubtypesOrIsPrototype(Map<Class<?>, String> entitySubtypes,
                                                    BeanDefinition<?> beanDefinition) {
        return !entitySubtypes.isEmpty() || !beanDefinition.isSingleton(); // revise if not singleton logic
    }

    @Override
    public void process(BeanDefinition<?> beanDefinition, BeanContext object) {
        BeanDefinition<?> topBeanDefinition = beanContext.getBeanDefinition(topAnnotatedEntityType(beanDefinition.getBeanType()));
        hierarchy.compute(topBeanDefinition, (type, subtypes) -> {
            if (subtypes == null) {
                subtypes = new ArrayList<>();
            }
            if (!type.equals(beanDefinition)) {
                subtypes.add(beanDefinition);
            }
            return subtypes;
        });
    }


    @PreDestroy
    void preDestroy() {
        //noinspection TypeParameterExplicitlyExtendsObject
        for (Map.Entry<BeanDefinition<?>, Collection<BeanDefinition<?>>> entity : hierarchy.entrySet()) {
            BeanDefinition<?> entityType = entity.getKey();
            Collection<BeanDefinition<?>> entitySubtypes = entity.getValue();

        /*    String registrarBeanName = entityPrototype + "$$Registrar";
            if (beanFactory.containsBeanDefinition(registrarBeanName)) {
                logger.info("Registrar for {} already available. Skipping configuration", entityPrototype);
                break;
            }

            if (hasSubtypesOrIsPrototype(entitySubtypes, beanFactory, entityPrototype) && entityType != null) {
                AnnotationUtils
                        .findAnnotationAttributes(entityType, EventSourced.class)
                        .map(props ->
                                     beanContext.createBean(
                                             MicronautEventSourcedEntityConfigurer.class,
                                             entityType,
                                             Objects.requireNonNull(props.get(ID_TYPE_CLASS),
                                                                    "Id type must be provided for "
                                                                            + entityPrototype)
                                     ));
            }*/
        }
        hierarchy.clear();
    }
}
