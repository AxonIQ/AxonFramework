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

package org.axonframework.eventsourcing.annotation;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.annotation.TargetEntityId;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to indicate that a method resolves the {@link EventCriteria} used to source the decision model of an
 * {@link EventSourcedEntity}, based on the {@link TargetEntityId} when loading it.
 * <p>
 * Use this annotation, rather than the shared {@link EventCriteriaBuilder}, when the events that build the entity's
 * decision model should differ from the events that guard its resulting append against concurrent conflicts. Pair it
 * with {@link AppendCriteriaBuilder} to declare the append-side criteria explicitly; when no
 * {@link AppendCriteriaBuilder} is present for the same identifier type, the shared {@link EventCriteriaBuilder} (if
 * any) or the entity's tag-based fallback is used for appending instead.
 * <p>
 * The method should be a static method that returns a non-null {@link EventCriteria} instance. The first argument
 * should be the identifier of the entity to load. If you need to resolve multiple identifier types, you can use this
 * annotation on multiple methods, as long as each declares a different identifier type.
 * <p>
 * Remaining parameters may resolve the {@link ProcessingContext}, the entire {@link Configuration}, or any component
 * registered in the {@link Configuration}.
 *
 * @author Mateusz Nowak
 * @see AppendCriteriaBuilder
 * @see EventCriteriaBuilder
 * @see EventSourcedEntity
 * @since 5.3.0
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SourcingCriteriaBuilder {

}
