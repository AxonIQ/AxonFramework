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

package org.axonframework.spring.stereotype;

import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that informs Axon's auto configurer for Spring that a given {@link Component} is a saga instance.
 * <p>
 * Requires the {@code axon-legacy} module on the classpath, which carries the Saga itself. The Saga is registered on
 * an event processor named after the Saga type, {@code <SagaName>Processor}, unless a
 * {@link org.axonframework.messaging.core.annotation.Namespace @Namespace} on the Saga or an explicit
 * {@code EventProcessorDefinition} says otherwise. That processor is configured through the regular
 * {@code axon.eventhandling.processors.<SagaName>Processor} properties. Sagas take part in the same processor
 * assignment pass as ordinary event handlers, so a Saga and an ordinary handler resolving to the same processor name
 * share that processor, as they did under one {@code @ProcessingGroup} in Axon Framework 4.
 * <p>
 * The stereotype only lets Spring discover the type; Axon owns the lifecycle of Saga instances. Spring
 * collaborators are resolved as parameters of a {@link SagaEventHandler @SagaEventHandler} method, not injected into
 * Saga fields. A Saga type must therefore be public and expose an accessible no-argument constructor.
 * <p>
 * Sagas carry the Axon Framework 4 API, to ease migration of projects that cannot move off it in one go. This
 * annotation keeps its Axon Framework 4 package for that reason, so migrating Saga classes component-scan unchanged.
 *
 * @author Allard Buijze
 * @since 5.4.0
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Component
@Scope("prototype")
public @interface Saga {

    /**
     * Selects the name of the {@link SagaStore} bean. If left empty the saga will be stored in the Saga Store
     * configured in the global Axon Configuration.
     *
     * @return the name of the {@link SagaStore} bean to keep Sagas of this type in
     */
    String sagaStore() default "";
}
