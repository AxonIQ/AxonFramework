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

package org.axonframework.modelling.saga;

import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.stream.Stream;

import static org.axonframework.common.ReflectionUtils.fieldsOf;
import static org.axonframework.common.ReflectionUtils.methodsOf;

/**
 * Abstract implementation of a {@link ResourceInjector} for sagas that injects field and method resources. Resources
 * are provided by the concrete implementation.
 *
 * @author Allard Buijze
 */
public abstract class AbstractResourceInjector implements ResourceInjector {

    private static final Logger logger = LoggerFactory.getLogger(AbstractResourceInjector.class);

    private static final String[] DEFAULT_INJECT_ANNOTATIONS = {
            "javax.inject.Inject",
            "jakarta.inject.Inject",
            "org.springframework.beans.factory.annotation.Autowired"
    };

    @Override
    public void injectResources(Object saga) {
        injectFieldResources(saga);
        injectMethodResources(saga);
    }

    private void injectFieldResources(Object saga) {
        fieldsOf(saga.getClass()).forEach(
                field -> Stream.of(injectorAnnotationNames())
                               .filter(ann -> AnnotationUtils.isAnnotationPresent(field, ann))
                               .forEach(annotatedFields -> {
                                   Class<?> requiredType = field.getType();
                                   findResource(requiredType).ifPresent(resource -> injectFieldResource(saga, field, resource));
                               }));
    }

    /**
     * Returns a resource of given {@code requiredType} or an empty Optional if the resource is not registered with the
     * injector.
     *
     * @param requiredType the class of the resource to find
     * @param <R>          the resource type
     * @return an Optional that contains the resource if it was found
     */
    protected abstract <R> Optional<R> findResource(Class<R> requiredType);

    @SuppressWarnings("deprecation") // Suppressed ReflectionUtils#ensureAccessible
    private void injectFieldResource(Object saga, Field injectField, Object resource) {
        try {
            ReflectionUtils.ensureAccessible(injectField);
            injectField.set(saga, resource);
        } catch (IllegalAccessException e) {
            logger.warn("Unable to inject resource. Exception while setting field: ", e);
        }
    }

    private void injectMethodResources(Object saga) {
        methodsOf(saga.getClass()).forEach(
                method -> Stream.of(injectorAnnotationNames())
                                .filter(a -> AnnotationUtils.isAnnotationPresent(method, a))
                                .forEach(a -> {
                                    Class<?> requiredType = method.getParameterTypes()[0];
                                    findResource(requiredType).ifPresent(resource -> injectMethodResource(saga, method, resource));
                                }));
    }

    @SuppressWarnings("deprecation") // Suppressed ReflectionUtils#ensureAccessible
    private void injectMethodResource(Object saga, Method injectMethod, Object resource) {
        try {
            ReflectionUtils.ensureAccessible(injectMethod);
            injectMethod.invoke(saga, resource);
        } catch (IllegalAccessException e) {
            logger.warn("Unable to inject resource. Exception while invoking setter: ", e);
        } catch (InvocationTargetException e) {
            logger.warn("Unable to inject resource. Exception while invoking setter: ", e.getCause());
        }
    }

    /**
     * Provides an array with fully qualified class names of the annotation that indicate an injection point for a
     * resource. By default, these are: <ul>
     * <li>{@code javax.inject.Inject}, and </li>
     * <li>{@code org.springframework.beans.factory.annotation.Autowired}</li>
     * </ul>
     *
     * @return an array with fully qualified class names of annotation
     */
    protected String[] injectorAnnotationNames() {
        return DEFAULT_INJECT_ANNOTATIONS;
    }
}
