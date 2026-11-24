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

import org.axonframework.messaging.eventstreaming.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Field or method level annotation that marks a field or method providing the Tag for the Event. The member name will
 * be used as the {@link Tag#key()} by default, except for Map values without
 * an explicit key (see below).
 * <p>
 * For both fields and methods, the value is obtained by calling {@code toString()} on the field value or method return
 * value. If the value (returned from {@code toString()} is null), no tag will be created. This means in the following
 * example if a class contains the orderId field as below the created Tag will have key "orderId" and the value
 * "Order:123".
 * <pre>
 * {@literal @}EventTag OrderId orderId = new OrderId("123")
 *
 * record OrderId(String id) {
 *     {@literal @}Override
 *     public String toString() {
 *         return "Order" + ":" + id;
 *     }
 * }
 * </pre>
 * <p>
 * Special handling is provided for Iterable and Map:
 * <ul>
 *     <li>For {@link Iterable} values:
 *         <ul>
 *             <li>A separate tag is created for each non-null element in the iterable.</li>
 *             <li>All tags use the same key (from annotation or member name).</li>
 *             <li>The tag value is obtained by calling toString() on each element.</li>
 *             <li>Example: {@code @EventTag List<Integer> numbers = List.of(1, 2)} creates tags:
 *                 {@code Tag("numbers", "1")} and {@code Tag("numbers", "2")}</li>
 *         </ul>
 *     </li>
 *     <li>For {@link java.util.Map} values:
 *         <ul>
 *             <li>If no key is provided in the annotation:
 *                 <ul>
 *                     <li>Map keys are used as tag keys (member name is ignored).</li>
 *                     <li>Map values are used as tag values.</li>
 *                     <li>Example: {@code @EventTag Map<String,String> map = Map.of("k1", "v1", "k2", "v2")}
 *                         creates tags: {@code Tag("k1", "v1")} and {@code Tag("k2", "v2")}</li>
 *                 </ul>
 *             </li>
 *             <li>If a key is provided in the annotation:
 *                 <ul>
 *                     <li>The provided key is used for all tags (map keys are ignored)</li>
 *                     <li>A tag is created for each non-null value in the map</li>
 *                     <li>Example: {@code @EventTag(key="custom") Map<String,String> map = Map.of("k1", "v1", "k2", "v2")}
 *                         creates tags: {@code Tag("custom", "v1")} and {@code Tag("custom", "v2")}</li>
 *                 </ul>
 *             </li>
 *         </ul>
 *     </li>
 * </ul>
 * <p>
 * If placed on a method, that method must contain no parameters and returns non-void value.
 * <p>
 * This annotation is repeatable, allowing multiple tags to be created from the same field or method.
 * <p>
 * This annotation can also be used as a meta-annotation, to define custom tag annotations:
 * <pre>
 * {@literal @}Retention(RetentionPolicy.RUNTIME)
 * {@literal @}Target({ElementType.FIELD, ElementType.METHOD})
 * {@literal @}EventTag(key = "orderId")
 * public {@literal @}interface OrderIdTag {
 * }
 * </pre>
 * A member annotated with {@code @OrderIdTag} results in a tag with key {@code "orderId"}. When the custom annotation
 * declares a {@code key} attribute itself, its value overrides the {@link #key()} of every {@code EventTag}
 * meta-annotation on that custom annotation.
 * Custom annotations may be combined with direct use of this annotation on the same member, each resulting in tags.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(EventTags.class)
public @interface EventTag {

    /**
     * The key of the Tag which will be assigned to the Event. Optional. If left empty:
     * <ul>
     *     <li>For Map values: map keys will be used as tag keys.</li>
     *     <li>For all other values: the member name will be used (with "get" stripped for getter methods).</li>
     * </ul>
     *
     * @return The tag key.
     */
    String key() default "";
}