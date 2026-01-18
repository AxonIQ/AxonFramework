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
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.annotation.TargetEntityId;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to indicate that a method can be used to resolve the {@link EventCriteria} for <em>appending</em>
 * (consistency checking) based on the {@link TargetEntityId} when appending events for an {@link EventSourcedEntity}.
 * <p>
 * This annotation is used specifically to define which events should be checked for conflicts when appending
 * new events. For Dynamic Consistency Boundaries (DCB), this can differ from the criteria used for sourcing
 * (loading events). Use {@link SourceCriteriaBuilder} to define criteria for sourcing.
 * <p>
 * <b>Understanding Marker vs Criteria (Orthogonal Concerns):</b>
 * <ul>
 *   <li><b>ConsistencyMarker</b>: Represents the "read position" in the event stream.
 *       It is always extracted from the events returned by sourcing.
 *       This tells the system: "I have seen events up to this position."</li>
 *   <li><b>AppendCriteria</b>: Specifies WHICH events to check for conflicts
 *       after the marker position. This tells the system: "Check if any events matching
 *       this criteria exist after my read position."</li>
 * </ul>
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
 * {@link AppendCriteriaBuilder} annotation on multiple methods.
 * <p>
 * You can define any component from the {@link Configuration} as a parameter to the
 * method to be able to resolve the {@link EventCriteria}. You can also inject the entire configuration as a parameter
 * by declaring it as such. Note that the first parameter must be the identifier, and cannot be a component.
 * <p>
 * <b>Precedence Rules:</b>
 * <ol>
 *     <li>If {@link AppendCriteriaBuilder} is present, it is used for append criteria</li>
 *     <li>Otherwise, if {@link EventCriteriaBuilder} is present, it is used for append criteria</li>
 *     <li>Otherwise, the tag-based fallback is used (existing behavior)</li>
 * </ol>
 * <p>
 * <b>Note:</b> {@link AppendCriteriaBuilder} is allowed standalone (uses tag-based fallback for sourcing).
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @see SourceCriteriaBuilder
 * @see EventCriteriaBuilder
 * @see AppendCondition
 * @see TargetEntityId
 * @see EventSourcedEntity
 * @since 5.0.0
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AppendCriteriaBuilder {

}
