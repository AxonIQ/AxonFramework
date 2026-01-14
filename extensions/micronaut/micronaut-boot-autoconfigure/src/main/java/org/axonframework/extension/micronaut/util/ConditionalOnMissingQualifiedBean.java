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

import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.*;

/**
 * {@link Conditional} that only matches when for the specified bean class in the {@link BeanFactory} there is an
 * instance which has the given {@code qualifier} set on it.
 * <p>
 * The condition can only match the bean definitions that have been processed by the
 * application context so far and, as such, it is strongly recommended to use this
 * condition on auto-configuration classes only. If a candidate bean may be created by
 * another auto-configuration, make sure that the one using this condition runs after.
 *
 * @author Steven van Beelen
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnMissingQualifiedBeanCondition.class)
public @interface ConditionalOnMissingQualifiedBean {

    /**
     * The class type of bean that should be checked. The condition matches if the class specified is contained in the
     * {@link ApplicationContext}, together with the specified {@code qualifier}.
     */
    Class<?> beanClass() default Object.class;

    /**
     * The qualifier which all instances of the given {code beanClass} in the {@link ApplicationContext} will be matched
     * for. One may indicate that a qualifier should <em>not</em> be present by prefixing it with {@code !}, e.g:
     * {@code qualifier = "!unqualified"}.
     * <p>
     * Multiple qualifiers may be provided, separated with a comma ({@code ,}). In that case, a bean matches when it is
     * assigned one of the given qualifiers.
     */
    String qualifier();
}
