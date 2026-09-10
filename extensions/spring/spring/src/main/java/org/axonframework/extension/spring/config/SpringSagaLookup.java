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
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

/**
 * A {@link BeanDefinitionRegistryPostProcessor} implementation that scans for Saga types and registers a
 * {@link SpringSagaDescriptor descriptor} for each Saga found.
 * <p>
 * The descriptors are picked up by {@link SagaProcessorConfigurer}, which resolves each Saga's processor name and
 * settings the same way {@link DefaultProcessorModuleFactory} does for the plain event handling beans, but keeps the
 * two registrations separate so a Saga is never silently merged onto a processor a regular event handler also claims.
 *
 * @author Allard Buijze
 * @since 4.6.0
 */
@Internal
public class SpringSagaLookup implements BeanDefinitionRegistryPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SpringSagaLookup.class);

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!(beanFactory instanceof BeanDefinitionRegistry)) {
            logger.warn("Given bean factory is not a BeanDefinitionRegistry. Cannot auto-configure Sagas");
            return;
        }

        String[] sagas = beanFactory.getBeanNamesForAnnotation(Saga.class);
        for (String saga : sagas) {
            if (beanFactory.containsBeanDefinition(saga + "$$Registrar")) {
                logger.info("Registrar for {} already available. Skipping configuration", saga);
                continue;
            }

            Saga sagaAnnotation = beanFactory.findAnnotationOnBean(saga, Saga.class);
            Class<?> sagaType = beanFactory.getType(saga);

            BeanDefinitionBuilder beanDefinitionBuilder =
                    BeanDefinitionBuilder.genericBeanDefinition(SpringSagaDescriptor.class)
                                         .addConstructorArgValue(saga)
                                         .addConstructorArgValue(sagaType)
                                         .addConstructorArgValue(beanFactory);

            if (sagaAnnotation != null && !"".equals(sagaAnnotation.sagaStore())) {
                beanDefinitionBuilder.addPropertyValue("sagaStore", sagaAnnotation.sagaStore());
            }
            BeanDefinitionRegistry bdRegistry = (BeanDefinitionRegistry) beanFactory;
            bdRegistry.registerBeanDefinition(saga + "$$Registrar",
                                              beanDefinitionBuilder.getBeanDefinition());
        }
    }

    @Override
    public void postProcessBeanDefinitionRegistry(
            BeanDefinitionRegistry beanDefinitionRegistry
    ) throws BeansException {
        // No action required.
    }
}
