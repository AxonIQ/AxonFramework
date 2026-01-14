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

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.context.annotation.Condition;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * {@link Condition} implementation to check for a bean instance of a specific class *and* a specific qualifier on it.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
public class OnQualifiedBeanCondition extends AbstractQualifiedBeanCondition {

    public OnQualifiedBeanCondition() {
        super(ConditionalOnQualifiedBean.class.getName(), "beanClass", "qualifier");
    }

    @Override
    protected ConditionOutcome buildOutcome(boolean anyMatch, String message) {
        return new ConditionOutcome(anyMatch, message);
    }

}
