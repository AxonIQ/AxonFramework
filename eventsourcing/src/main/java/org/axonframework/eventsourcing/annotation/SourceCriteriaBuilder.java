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
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.annotation.TargetEntityId;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to indicate that a method can be used to resolve the {@link EventCriteria} for <em>sourcing</em>
 * (loading events) based on the {@link TargetEntityId} when loading an {@link EventSourcedEntity}.
 * <p>
 * This annotation is used specifically to define which events should be loaded when sourcing an entity's state.
 * For Dynamic Consistency Boundaries (DCB), this can differ from the criteria used for consistency checking
 * when appending events. Use {@link AppendCriteriaBuilder} to define criteria for consistency checking.
 * <p>
 * <b>Example - Accounting Use Case:</b>
 * <pre>{@code
 * @EventSourcedEntity(tagKey = "accountId")
 * public class Account {
 *
 *     // Source: Load CreditsIncreased AND CreditsDecreased to calculate balance
 *     @SourceCriteriaBuilder
 *     public static EventCriteria sourceCriteria(String accountId) {
 *         return EventCriteria
 *             .havingTags("accountId", accountId)
 *             .andBeingOneOfTypes("CreditsIncreased", "CreditsDecreased");
 *     }
 *
 *     // Append: Only check for conflicts on CreditsDecreased (allow concurrent increases)
 *     @AppendCriteriaBuilder
 *     public static EventCriteria appendCriteria(String accountId) {
 *         return EventCriteria
 *             .havingTags("accountId", accountId)
 *             .andBeingOneOfTypes("CreditsDecreased");
 *     }
 * }
 * }</pre>
 * <p>
 * The method should be a static method that returns an {@link EventCriteria} instance. The first argument should be the
 * identifier of the entity to load. If you need to resolve multiple identifier types, you can use the
 * {@link SourceCriteriaBuilder} annotation on multiple methods.
 * <p>
 * You can define any component from the {@link Configuration} as a parameter to the
 * method to be able to resolve the {@link EventCriteria}. You can also inject the entire configuration as a parameter
 * by declaring it as such. Note that the first parameter must be the identifier, and cannot be a component.
 * <p>
 * <b>Precedence Rules:</b>
 * <ol>
 *     <li>If {@link SourceCriteriaBuilder} is present, it is used for sourcing criteria</li>
 *     <li>Otherwise, if {@link EventCriteriaBuilder} is present, it is used for sourcing criteria</li>
 *     <li>Otherwise, the tag-based fallback is used (existing behavior)</li>
 * </ol>
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @see AppendCriteriaBuilder
 * @see EventCriteriaBuilder
 * @see TargetEntityId
 * @see EventSourcedEntity
 * @see SourcingCondition
 * @since 5.0.0
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SourceCriteriaBuilder {

}
