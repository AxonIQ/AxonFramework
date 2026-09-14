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
import org.axonframework.spring.stereotype.Saga;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

import java.util.function.Supplier;

/**
 * A {@link BeanDefinitionRegistryPostProcessor} implementation that scans for Saga types and registers a
 * {@link SpringSagaConfigurer configurer} for each Saga found.
 * <p>
 * A Saga is recognized by the {@link Saga @Saga} annotation on its bean definition. The configurer is registered under
 * the bean name {@code "<sagaBeanName>$$Registrar"}, which doubles as an opt-out: declaring that bean name yourself
 * keeps this lookup from configuring the Saga.
 * <p>
 * The work happens in {@link #postProcessBeanFactory(ConfigurableListableBeanFactory)} rather than in
 * {@link #postProcessBeanDefinitionRegistry(BeanDefinitionRegistry)}, since resolving the bean type and the annotation
 * attributes needs a bean factory.
 * <p>
 * This class is internal wiring: it is declared by the Saga auto configuration and never referenced from application
 * code, so its shape may change with the Saga support it serves.
 *
 * @author Allard Buijze
 * @since 5.4.0
 */
@Internal
public class SpringSagaLookup implements BeanDefinitionRegistryPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SpringSagaLookup.class);

    private static final String REGISTRAR_BEAN_NAME_SUFFIX = "$$Registrar";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!(beanFactory instanceof BeanDefinitionRegistry bdRegistry)) {
            logger.warn("Given bean factory is not a BeanDefinitionRegistry. Cannot auto-configure Sagas");
            return;
        }

        String[] sagas = beanFactory.getBeanNamesForAnnotation(Saga.class);
        for (String saga : sagas) {
            if (beanFactory.containsBeanDefinition(saga + REGISTRAR_BEAN_NAME_SUFFIX)) {
                logger.info("Registrar for {} already available. Skipping configuration", saga);
                // Literal port of Axon Framework 4: this aborts the whole loop rather than skipping this one Saga,
                // silently leaving every remaining Saga unconfigured. Pinned by SpringSagaLookupTest.
                break;
            }

            Saga sagaAnnotation = beanFactory.findAnnotationOnBean(saga, Saga.class);
            Class<?> sagaType = beanFactory.getType(saga);
            Supplier<BeanDefinition> sagaBeanDefinition = () -> beanFactory.getBeanDefinition(saga);

            BeanDefinitionBuilder beanDefinitionBuilder =
                    BeanDefinitionBuilder.genericBeanDefinition(SpringSagaConfigurer.class)
                                         .addConstructorArgValue(saga)
                                         .addConstructorArgValue(sagaType)
                                         .addConstructorArgValue(sagaBeanDefinition);

            if (sagaAnnotation != null && !"".equals(sagaAnnotation.sagaStore())) {
                beanDefinitionBuilder.addPropertyValue("sagaStore", sagaAnnotation.sagaStore());
            }
            bdRegistry.registerBeanDefinition(saga + REGISTRAR_BEAN_NAME_SUFFIX,
                                              beanDefinitionBuilder.getBeanDefinition());
        }
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        // No action required.
    }
}
