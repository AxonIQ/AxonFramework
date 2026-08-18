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

package org.axonframework.eventsourcing.annotation.reflection;

import org.axonframework.messaging.core.MessageTypeResolver;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that indicates that a method or constructor is a factory method for an event-sourced entity, forcing
 * creation at all times.
 * <p>
 * It is meta-annotated with {@link EntityCreator} to support all behavior as described on the {@code EntityCreator}.
 * What this annotation adds, is the assurance that <b>if</b> sufficient information is available to invoke the
 * annotated method or constructor, that it will be invoked regardless of downstream usages of the entity itself. This
 * adjusts the regular behavior of the no-arguments, identifier-only, and event-based {@code EntityCreator} solutions
 * like so:
 * <ol>
 *     <li>A no-arguments, {@code @EntityCreator} annotated constructor or method, will always create the entity.</li>
 *     <li>An identifier-based, {@code @EntityCreator} annotated constructor or method, will create if an identifier can be resolved.</li>
 *     <li>An event-based, {@code @EntityCreator} annotated constructor or method, will <b>only</b> create if an initial event is present.</li>
 * </ol>
 * <p>
 * This additional behavior becomes important for create-if-missing styled handlers. For plain {@code EntityCreator}
 * solutions, the create-if-missing handler should either (1) be {@code static} (read: marked as a creational handler)
 * when present in the entity itself or (2) moved out of the entity entirely. In both scenarios the create-if-missing
 * behavior would require the entity as a parameter to the message handling function validate if it does exist. If
 * optional subsequent decisions require life state changes of the potentially missing entity, the user is enforced to
 * inject a {@link org.axonframework.modelling.repository.ManagedEntity} instead.
 * <p>
 * This annotation adjusts that behavior, by simply invoking the {@code @ForcedEntityCreator} annotated constructor or
 * method and passing it through. This will effectively make the entity non-null for all no-argument scenarios and most
 * identifier-based scenarios. Furthermore, it allows the create-if-missing solution to work on instance command
 * handlers placed in the entity, resolving the need to make them {@code static}.
 * <p>
 * Note that this forced-creation-approach is viewed as an aggregate-centric solution, whereas this library aims to
 * steer away from that. Hence, any opportunity to use other mechanisms than this are encouraged.
 * <p>
 * As of 5.4.0 a no-arguments or identifier-based {@link EntityCreator} always creates the entity even without a first
 * event, which is exactly the behavior this annotation used to force. That makes {@code @ForcedEntityCreator}
 * redundant: it now behaves identically to {@link EntityCreator}. It remains meta-annotated with {@link EntityCreator},
 * so annotated elements are still discovered and invoked as regular creators and existing code keeps working, but new
 * code should use {@link EntityCreator} directly.
 *
 * @author Steven van Beelen
 * @since 5.3.1
 * @deprecated Since 5.4.0 a no-arguments or identifier-based {@link EntityCreator} always creates the entity, making
 * this annotation redundant. Use {@link EntityCreator} directly instead.
 */
@Deprecated(since = "5.4.0", forRemoval = true)
@EntityCreator
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.ANNOTATION_TYPE})
public @interface ForcedEntityCreator {

    /**
     * The qualified names of the payload types that this factory method can handle. If a payload parameter is declared,
     * and this value is left at default, the payload's qualified name will be determined based on the
     * {@link MessageTypeResolver}.
     *
     * @return the qualified names of the payload types that this factory method can handle
     */
    String[] payloadQualifiedNames() default {};
}
