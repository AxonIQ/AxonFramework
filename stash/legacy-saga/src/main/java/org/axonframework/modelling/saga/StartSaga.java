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

import org.axonframework.messaging.core.annotation.HasHandlerAttributes;

import java.lang.annotation.*;

/**
 * Indicates that the annotated {@link SagaEventHandler} method can trigger the
 * creation of a new Saga instance.
 * <p/>
 * When a Saga is started due to an invocation on a StartSaga annotated method, the association of the annotated method
 * and the actual property's value are used to define a AssociationValue for the created saga. Thus, a method with this
 * definition:
 * <p/>
 * <code>@StartSaga(forceNew=true)<br/>@SagaEventHandler(associationProperty=&quot;orderId&quot;)<br/>public void
 * handleOrderCreated(OrderCreatedEvent event) </code><br/> will always trigger the creation of a saga that can be found
 * with an AssociationValue with key "orderId" and as value the value returned by {@code event.getOrderId()}.
 * <p/>
 * This annotation can only appear on methods that have been annotated with {@link
 * SagaEventHandler @SagaEventHandler}.
 *
 * @author Allard Buijze
 * @since 0.7
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@HasHandlerAttributes
public @interface StartSaga {

    /**
     * Indicates whether or not to force creation of a Saga, even if one already exists. If {@code true}, a new
     * Saga is always created when an event assignable to the annotated method is handled. If {@code false}, a new
     * saga is only created if no Saga's exist that can handle the incoming event.
     * <p/>
     * This annotation can only appear on methods that have been annotated with {@link
     * SagaEventHandler @SagaEventHandler}.
     */
    boolean forceNew() default false;
}
