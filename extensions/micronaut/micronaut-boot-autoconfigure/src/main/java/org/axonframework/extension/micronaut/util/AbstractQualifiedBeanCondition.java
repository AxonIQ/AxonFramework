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

package org.axonframework.extension.micronaut.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.MultiValueMap;

import java.lang.invoke.MethodHandles;
import java.util.stream.Stream;
import jakarta.annotation.Nonnull;

import static org.axonframework.extension.micronaut.SpringUtils.isQualifierMatch;

/**
 * Abstract implementations for conditions that match against the availability of beans of a specific type with a
 * given qualifier.
 */
public abstract class AbstractQualifiedBeanCondition extends SpringBootCondition implements ConfigurationCondition {
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final String annotationName;
    private final String beanClassAttribute;
    private final String qualifierAttribute;

    /**
     * Initialize the condition, looking for properties on a given annotation
     *
     * @param annotationName     The fully qualified class name of the annotation to find attributes on.
     * @param beanClassAttribute The attribute containing the bean class.
     * @param qualifierAttribute The attribute containing the qualifier.
     */
    public AbstractQualifiedBeanCondition(String annotationName, String beanClassAttribute, String qualifierAttribute) {
        this.annotationName = annotationName;
        this.beanClassAttribute = beanClassAttribute;
        this.qualifierAttribute = qualifierAttribute;
    }

    @Nonnull
    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        MultiValueMap<String, Object> annotationAttributes =
                metadata.getAllAnnotationAttributes(annotationName, true);

        String beanType = (String) annotationAttributes.getFirst(beanClassAttribute);
        String qualifierAttr = (String) annotationAttributes.getFirst(qualifierAttribute);
        String qualifier;
        boolean qualifierMatch;
        if (qualifierAttr.startsWith("!")) {
            qualifier = qualifierAttr.substring(1);
            qualifierMatch = false;
        } else {
            qualifier = qualifierAttr;
            qualifierMatch = true;
        }
        String[] qualifiers = qualifier.split(",");
        Class conditionalClass;
        try {
            conditionalClass = Class.forName(beanType);
        } catch (ClassNotFoundException e) {
            String failureMessage = String.format(
                    "Failed to extract a class instance for fully qualified class name [%s]",
                    beanType
            );
            logger.warn(failureMessage, e);
            return new ConditionOutcome(false, failureMessage);
        }

        ConfigurableListableBeanFactory bf = context.getBeanFactory();
        boolean anyMatch = Stream.of(bf.getBeanNamesForType(conditionalClass))
                                 .anyMatch(beanName -> qualifierMatch == isOneMatching(beanName, bf, qualifiers));
        String message = anyMatch
                ? String.format("Match found for class [%s] and qualifier [%s]", conditionalClass, qualifier)
                : String.format("No match found for class [%s] and qualifier [%s]", conditionalClass, qualifier);
        return buildOutcome(anyMatch, message);
    }

    private boolean isOneMatching(String beanName, ConfigurableListableBeanFactory bf, String[] qualifiers) {
        for (String qualifier : qualifiers) {
            if (isQualifierMatch(beanName, bf, qualifier)) {
                return true;
            }
        }
        return false;
    }

    protected abstract ConditionOutcome buildOutcome(boolean anyMatch, String message);
}
