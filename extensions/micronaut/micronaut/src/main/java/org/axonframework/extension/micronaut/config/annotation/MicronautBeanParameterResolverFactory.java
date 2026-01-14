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

package org.axonframework.extension.micronaut.config.annotation;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.axonframework.common.Priority;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * ParameterResolverFactory implementation that resolves parameters in the Spring Application Context. A parameter can
 * be resolved as a Spring bean if there is exactly one bean assignable to the parameter type. If multiple beans are
 * available the desired one can be designated with a {@link org.springframework.beans.factory.annotation.Qualifier}
 * annotation on the parameter. By absence of a {@link org.springframework.beans.factory.annotation.Qualifier}
 * annotation the bean marked as primary will be chosen. Note that when multiple beans are marked as primary, either one
 * can be selected as parameter value.
 *
 * @author Allard Buijze
 * @since 2.1
 */
//@SuppressWarnings("PackageAccessibility")
//@Priority(Priority.LOW)
//public class MicronautBeanParameterResolverFactory implements ParameterResolverFactory, ApplicationContextAware {
//
//    private static final Logger logger = LoggerFactory.getLogger(MicronautBeanParameterResolverFactory.class);
//
//    private ApplicationContext applicationContext;
//
//    /**
//     * Default constructor, which relies on Spring to inject the application context.
//     */
//    public MicronautBeanParameterResolverFactory() {
//    }
//
//    /**
//     * Convenience constructor to use when an instance is not managed by Spring, but an application context is
//     * available.
//     *
//     * @param applicationContext The application context providing access to beans that may be used as parameters
//     */
//    public MicronautBeanParameterResolverFactory(ApplicationContext applicationContext) {
//        this.applicationContext = applicationContext;
//    }
//
//    @Nullable
//    @Override
//    public ParameterResolver<?> createInstance(@Nonnull Executable executable, @Nonnull Parameter[] parameters, int parameterIndex) {
//        if (applicationContext == null) {
//            return null;
//        }
//        Class<?> parameterType = parameters[parameterIndex].getType();
//        String[] beansFound = applicationContext.getBeanNamesForType(parameterType);
//        if (beansFound.length == 0) {
//            return null;
//        } else if (beansFound.length > 1) {
//            final AutowireCapableBeanFactory beanFactory = applicationContext.getAutowireCapableBeanFactory();
//            if (beanFactory instanceof ConfigurableListableBeanFactory) {
//                Optional<ParameterResolver<?>> resolver = findQualifiedBean(
//                        beansFound, (ConfigurableListableBeanFactory) beanFactory, parameters, parameterIndex
//                );
//                if (resolver.isPresent()) {
//                    return resolver.get();
//                }
//            }
//            if (logger.isWarnEnabled()) {
//                logger.warn(
//                        "{} beans of type {} found, but none was marked as primary and parameter lacks @Qualifier. "
//                                + "Ignoring this parameter.",
//                        beansFound.length, parameterType.getSimpleName()
//                );
//            }
//            return null;
//        } else {
//            return new SpringBeanParameterResolver(applicationContext.getAutowireCapableBeanFactory(), beansFound[0]);
//        }
//    }
//
//    private Optional<ParameterResolver<?>> findQualifiedBean(String[] beansFound,
//                                                             ConfigurableListableBeanFactory clBeanFactory,
//                                                             Parameter[] parameters,
//                                                             int parameterIndex) {
//        final Parameter parameter = parameters[parameterIndex];
//        // find @Qualifier matching candidate
//        final Optional<Map<String, Object>> qualifier =
//                AnnotationUtils.findAnnotationAttributes(parameter, Qualifier.class);
//        if (qualifier.isPresent()) {
//            for (String bean : beansFound) {
//                if (SpringUtils.isQualifierMatch(bean, clBeanFactory, (String) qualifier.get().get("qualifier"))) {
//                    return Optional.of(new SpringBeanParameterResolver(clBeanFactory, bean));
//                }
//            }
//        }
//        // find @Primary matching candidate
//        for (String bean : beansFound) {
//            if (clBeanFactory.containsBeanDefinition(bean) && clBeanFactory.getBeanDefinition(bean).isPrimary()) {
//                return Optional.of(new SpringBeanParameterResolver(clBeanFactory, bean));
//            }
//        }
//        return Optional.empty();
//    }
//
//    @SuppressWarnings("NullableProblems")
//    @Override
//    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
//        this.applicationContext = applicationContext;
//    }
//
//    private static class SpringBeanParameterResolver implements ParameterResolver<Object> {
//
//        private final AutowireCapableBeanFactory beanFactory;
//        private final String beanName;
//
//        public SpringBeanParameterResolver(AutowireCapableBeanFactory beanFactory, String beanName) {
//            this.beanFactory = beanFactory;
//            this.beanName = beanName;
//        }
//
//        @Nonnull
//        @Override
//        public CompletableFuture<Object> resolveParameterValue(@Nonnull ProcessingContext context) {
//            return CompletableFuture.completedFuture(beanFactory.getBean(beanName));
//        }
//
//        @Override
//        public boolean matches(@Nonnull ProcessingContext context) {
//            return true;
//        }
//    }
//}
