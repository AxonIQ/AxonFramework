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

package org.axonframework.messaging.core.annotation;

import org.axonframework.messaging.core.sequencing.SequentialPolicy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify a sequencing policy for message handling methods or classes.
 * <p>
 * The sequencing policy determines how messages are processed in relation to each other,
 * controlling whether messages should be processed sequentially or in parallel.
 * <p>
 * Currently this annotation is only supported on event handling methods and components.
 * <p>
 * This annotation can be applied either directly to event handler methods or to the declaring class.
 * When applied to a class, all event handler methods in that class will inherit the sequencing policy.
 * Method-level annotations take precedence over class-level annotations.
 * <p>
 * On both methods and classes, this annotation may also be used as a meta-annotation on a custom annotation, for
 * example to give a commonly used sequencing policy a meaningful name.
 *
 * <h3>Sequencing Policy Implementation Requirements</h3>
 * <p>
 * Custom sequencing policy implementations must adhere to the following constructor parameter rules:
 * <ul>
 *   <li><strong>First parameter (optional):</strong> If the payload type is needed, it must be the first
 *       parameter and must be of type {@code Class<?>}. This will be automatically injected with the
 *       event's payload type.</li>
 *   <li><strong>Remaining parameters:</strong> All other constructor parameters must be primitives
 *       (int, long, boolean, double, etc.), their wrapper types, or String. These values are provided
 *       as strings in the {@link #parameters()} array and will be automatically converted to the
 *       appropriate types.</li>
 * </ul>
 *
 * <h3>Parameter Matching</h3>
 * <p>
 * The framework matches constructors by counting non-Class parameters. If your constructor has a
 * {@code Class<?>} parameter as the first parameter, it is excluded from the parameter count matching.
 * The number of strings in {@link #parameters()} must exactly match the number of non-Class constructor parameters.
 *
 * <h3>Supported Parameter Types</h3>
 * <p>
 * The following types are supported for constructor parameters (excluding the optional Class parameter):
 * <ul>
 *   <li>String</li>
 *   <li>int, Integer</li>
 *   <li>long, Long</li>
 *   <li>boolean, Boolean</li>
 *   <li>double, Double</li>
 *   <li>float, Float</li>
 *   <li>byte, Byte</li>
 *   <li>short, Short</li>
 *   <li>char, Character (single character strings only)</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *
 * // Using MetadataSequencingPolicy with metadata key
 * @EventHandler
 * @SequencingPolicy(type = MetadataSequencingPolicy.class, parameters = {"userId"})
 * public void handle(OrderUpdatedEvent event) { ... }
 *
 * // Using PropertySequencingPolicy with property name
 * @EventHandler
 * @SequencingPolicy(type = PropertySequencingPolicy.class, parameters = {"aggregateId"})
 * public void handle(OrderCreatedEvent event) { ... }
 *
 * // Custom policy with payload type and additional parameters
 * @EventHandler
 * @SequencingPolicy(type = CustomPolicy.class, parameters = {"10", "true"})
 * public void handle(OrderEvent event) { ... }
 *
 * // Example CustomPolicy constructor implementation:
 * public record CustomPolicy(Class<?> payloadType, int intParam, boolean booleanParam) implements SequencingPolicy {
 * }
 * }</pre>
 *
 * <h3>Error Conditions</h3>
 * <p>
 * The following conditions will result in an {@link UnsupportedHandlerException}:
 * <ul>
 *   <li>No constructor found matching the parameter count</li>
 *   <li>Class parameter is not the first parameter in the constructor</li>
 *   <li>Unsupported parameter type used in constructor</li>
 *   <li>Invalid parameter value conversion (e.g., non-numeric string for int parameter)</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@HasHandlerAttributes
public @interface SequencingPolicy {

    /**
     * The sequencing policy implementation class to use.
     * <p>
     * The specified class must implement {@link org.axonframework.messaging.core.sequencing.SequencingPolicy}
     * and follow the constructor parameter requirements described in the class documentation.
     * <p>
     * The framework will attempt to instantiate this class using either:
     * <ul>
     *   <li>A no-argument constructor (when {@link #parameters()} is empty)</li>
     *   <li>A constructor matching the parameter count and types (when parameters are provided)</li>
     * </ul>
     * <p>
     * If the constructor requires the event payload type, it must be the first parameter of type {@code Class<?>}
     * and will be automatically injected by the framework.
     *
     * @return The sequencing policy class to instantiate.
     */
    Class<? extends org.axonframework.messaging.core.sequencing.SequencingPolicy> type() default SequentialPolicy.class;

    /**
     * String parameters to pass to the sequencing policy constructor.
     * @return String parameters to pass to the sequencing policy constructor.
     */
    String[] parameters() default {};
}
