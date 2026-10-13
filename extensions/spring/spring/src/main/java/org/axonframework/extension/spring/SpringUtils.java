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

package org.axonframework.extension.spring;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.type.MethodMetadata;
import org.springframework.util.ObjectUtils;

import java.lang.reflect.Method;
import java.util.Map;

import static org.springframework.core.annotation.AnnotationUtils.getAnnotation;

/**
 * Utility class for Spring specific helper functions.
 *
 * @author Steven van Beelen
 * @since 3.1
 */
public final class SpringUtils {

    private SpringUtils() {
        // Private constructor to enforce as utility class.
    }

    /**
     * Match if the given {@code qualifier} can be found on the
     * {@link org.springframework.beans.factory.config.BeanDefinition} of the given {@code beanName}.
     * Uses a {@link org.springframework.beans.factory.config.ConfigurableListableBeanFactory} to find the required
     * information on the bean definition.
     * <p/>
     * Copied from {@link org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils}, plus an additional
     * function to check for the Qualifier annotation on a factory method and some style adjustments.
     *
     * @param beanName    A {@link java.lang.String} for the bean name to check its qualifier for.
     * @param beanFactory A {@link org.springframework.beans.factory.config.ConfigurableListableBeanFactory} used to
     *                    retrieve information about the given {@code beanName}.
     * @param qualifier   A {@link java.lang.String} for the {@code qualifier} we trying to match with the qualifier of
     *                    the given {@code beanName}.
     * @return True if the {@code qualifier} is found on the {@code beanName}; false if it is not found.
     */
    public static boolean isQualifierMatch(String beanName,
                                           ConfigurableListableBeanFactory beanFactory,
                                           String qualifier) {
        return isQualifierMatch(beanName, beanFactory, qualifier, true);
    }

    /**
     * Match if the given {@code qualifier} can be found on the
     * {@link org.springframework.beans.factory.config.BeanDefinition} of the given {@code beanName}, like
     * {@link #isQualifierMatch(String, ConfigurableListableBeanFactory, String)} does.
     * <p>
     * With {@code allowFactoryBeanInit} set to {@code false}, the match is made without initializing any
     * {@link org.springframework.beans.factory.FactoryBean}. A qualifier on the class of an object created by a
     * {@code FactoryBean} is then only found when the type of that object can be determined from the bean definition.
     * This allows checking qualifiers while the application context is still creating its beans.
     *
     * @param beanName             the name of the bean to check the qualifier of
     * @param beanFactory          the bean factory to retrieve information about the given {@code beanName} from
     * @param qualifier            the qualifier to match with the qualifier of the given {@code beanName}
     * @param allowFactoryBeanInit whether a {@link org.springframework.beans.factory.FactoryBean} may be initialized
     *                             to determine the type of the bean
     * @return {@code true} if the {@code qualifier} is found on the {@code beanName}, {@code false} otherwise
     * @since 5.5.0
     */
    public static boolean isQualifierMatch(String beanName,
                                           ConfigurableListableBeanFactory beanFactory,
                                           String qualifier,
                                           boolean allowFactoryBeanInit) {
        if (!beanFactory.containsBean(beanName)) {
            return false;
        }

        try {
            BeanDefinition bd = beanFactory.getMergedBeanDefinition(beanName);

            // Check for Qualifier annotation on the FactoryMethod
            if (bd instanceof AnnotatedBeanDefinition) {
                MethodMetadata factoryMethodMetadata = ((AnnotatedBeanDefinition) bd).getFactoryMethodMetadata();
                Map<String, Object> qualifierAttributes =
                        factoryMethodMetadata.getAnnotationAttributes(Qualifier.class.getName());
                if (qualifierAttributes != null && qualifier.equals(qualifierAttributes.get("value"))) {
                    return true;
                }
            }

            // Explicit qualifier metadata on bean definition? (typically in XML definition)
            if (bd instanceof AbstractBeanDefinition) {
                AbstractBeanDefinition abd = (AbstractBeanDefinition) bd;
                AutowireCandidateQualifier candidate = abd.getQualifier(Qualifier.class.getName());
                if ((candidate != null &&
                        qualifier.equals(candidate.getAttribute(AutowireCandidateQualifier.VALUE_KEY))) ||
                        qualifier.equals(beanName) ||
                        ObjectUtils.containsElement(beanFactory.getAliases(beanName), qualifier)) {
                    return true;
                }
            }

            // Corresponding qualifier on factory method? (typically in configuration class)
            if (bd instanceof RootBeanDefinition) {
                Method factoryMethod = ((RootBeanDefinition) bd).getResolvedFactoryMethod();
                if (factoryMethod != null) {
                    Qualifier targetAnnotation = getAnnotation(factoryMethod, Qualifier.class);
                    if (targetAnnotation != null) {
                        return qualifier.equals(targetAnnotation.value());
                    }
                }
            }

            // Corresponding qualifier on bean implementation class? (for custom user types)
            Class<?> beanType = beanFactory.getType(beanName, allowFactoryBeanInit);
            if (beanType != null) {
                Qualifier targetAnnotation = getAnnotation(beanType, Qualifier.class);
                if (targetAnnotation != null) {
                    return qualifier.equals(targetAnnotation.value());
                }
            }
        } catch (NoSuchBeanDefinitionException ex) {
            // Ignore - can't compare qualifiers for a manually registered singleton object
        }
        return false;
    }
}
