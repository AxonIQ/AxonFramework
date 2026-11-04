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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation describing a {@link org.axonframework.messaging.core.QualifiedName} through its separate
 * {@link #namespace()} and {@link #name() (local) name}, so that users never have to know how both parts are
 * concatenated into a single {@link String}.
 * <p>
 * This annotation serves two purposes:
 * <ol>
 *     <li>It is the meta-annotation that gives the message annotations, like
 *     {@link org.axonframework.messaging.eventhandling.annotation.Event @Event}, their {@code namespace} and
 *     {@code name} attributes (through {@link Message @Message}). This annotation is in turn meta-annotated with
 *     {@link Namespace @Namespace}. Since Axon merges the attributes along the meta-annotation hierarchy, the
 *     {@code value} of {@code @Namespace} is exposed as the {@code namespace} attribute, which the {@link #namespace()}
 *     of this annotation and of the message annotations override.</li>
 *     <li>It is used as an attribute value on handler annotations to refer to the name of a message, mirroring the
 *     attributes of the message annotation. For example, the
 *     {@link org.axonframework.messaging.eventhandling.annotation.EventHandler#event()} attribute.</li>
 * </ol>
 * <p>
 * When used to refer to a message on a handler annotation, an empty {@link #namespace()} or {@link #name()} is taken
 * from the {@code QualifiedName} the handler's payload type resolves to through the configured
 * {@link org.axonframework.messaging.core.MessageTypeResolver}. This mirrors how a message annotation derives undefined
 * attributes from the annotated class. As such, both parts only have to be defined when they differ from the payload
 * type's own name. Note that the payload type's name is split into a namespace and local name at its <b>last</b> dot.
 * For example, given a payload type annotated with {@code @Event(namespace = "orders", name = "v2.OrderPlaced")}, the
 * declaration {@code @QualifiedName(namespace = "legacy")} resolves to {@code legacy.OrderPlaced}. A resolved name
 * always consists of a namespace and a local name.
 * <p>
 * Example usage, handling both the current and a legacy name of an event:
 * <pre>{@code
 * @Event(namespace = "com.acme")
 * public record OrderPlacedEvent(String id) { }
 *
 * class OrderProjection {
 *
 *     @EventHandler(event = {
 *             @QualifiedName(namespace = "com.acme", name = "OrderPlacedEvent"),
 *             @QualifiedName(namespace = "com.acme.legacy", name = "OrderPlaced")
 *     })
 *     void on(OrderPlacedEvent event) { ... }
 * }
 * }</pre>
 * <p>
 * Note that this annotation shares its simple name with {@link org.axonframework.messaging.core.QualifiedName}. When
 * both are used in the same file, one of them has to be referred to by its fully qualified class name.
 *
 * @author Allard Buijze
 * @since 5.5.0
 */
@Namespace
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface QualifiedName {

    /**
     * The namespace or (bounded) context of the message, reflecting the
     * {@link org.axonframework.messaging.core.QualifiedName#namespace()}.
     * <p>
     * When empty, the namespace is derived. On a message annotation, it defaults to the {@link Namespace} declared on
     * the type, its enclosing types, package, or module, and otherwise to the package name of the annotated class. When
     * referring to a message from a handler annotation, it defaults to the namespace of the handler's payload type.
     *
     * @return the namespace or (bounded) context of the message
     */
    String namespace() default "";

    /**
     * The business or domain name of the message, reflecting the
     * {@link org.axonframework.messaging.core.QualifiedName#localName()}.
     * <p>
     * When empty, the name is derived. On a message annotation, it defaults to the simple name of the annotated class.
     * When referring to a message from a handler annotation, it defaults to the local name of the handler's payload
     * type.
     *
     * @return the business or domain name of the message
     */
    String name() default "";
}
