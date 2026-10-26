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

package org.axonframework.messaging.eventhandling.annotation;

import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.MessageHandler;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.QualifiedName;
import org.axonframework.messaging.eventhandling.EventMessage;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to be placed on methods that can handle {@link EventMessage events}, thus making them
 * {@link org.axonframework.messaging.eventhandling.EventHandler EventHandlers}.
 * <p>
 * Event handler annotated methods are typically subscribed with a
 * {@link org.axonframework.messaging.eventhandling.processing.EventProcessor} as part of an
 * {@link AnnotatedEventHandlingComponent}.
 * <p>
 * The parameters of the annotated method are resolved using parameter resolvers. Axon provides a number of parameter
 * resolvers that allow you to use the following parameter types:<ul>
 * <li>The first parameter is always the {@link EventMessage#payload() payload} of the {@code EventMessage}.
 * <li>Parameters annotated with {@link org.axonframework.messaging.core.annotation.MetadataValue} will resolve to the
 * {@link org.axonframework.messaging.core.Metadata} value with the key as indicated on the annotation. If required is
 * false (default), null is passed when the metadata value is not present. If required is true, the resolver will not
 * match and prevent the method from being invoked when the metadata value is not present.</li>
 * <li>Parameters of type {@code Metadata} will have the entire {@link EventMessage#metadata() event message metadata}
 * injected.</li>
 * <li>Parameters of type {@link java.time.Instant} annotated with {@link Timestamp @Timestamp} will resolve to the
 * {@link EventMessage#timestamp() timestamp} of the {@code EventMessage}. This is the time at which the Event was
 * generated.</li>
 * <li>Parameters assignable to {@link org.axonframework.messaging.core.Message} will have the entire {@link
 * EventMessage} injected (if the message is assignable to that parameter). If the first parameter is of type message,
 * it effectively matches an event of any type. Due to type erasure, Axon cannot detect what parameter is expected. In
 * such case, it is best to declare a parameter of the payload type, followed by a parameter of type
 * {@code Message}.</li>
 * <li>A parameter of type {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} will inject the active
 * processing context at that moment in time.</li>
 * </ul>
 * <p>
 * For each event, all matching methods will be invoked per object instance with annotated methods. If still method is
 * found, the event listener ignores the event.</ol>
 * <p>
 * Note: if there are two event handler methods accepting the same argument, the order in which they are invoked is
 * undefined.
 *
 * @author Allard Buijze
 * @see AnnotatedEventHandlingComponent
 * @see ParameterResolverFactory
 * @since 0.1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@MessageHandler(messageType = EventMessage.class)
public @interface EventHandler {

    /**
     * The name of the event this handler listens to, matching to the {@link MessageType#name()} from the
     * {@link EventMessage#type()}.
     * <p>
     * The name is a fully qualified name (namespace and local name concatenated with a dot) and is used as is. When
     * both this attribute and {@link #event()} are defined, this handler listens to the combined set of names. When
     * neither is defined, the {@link org.axonframework.messaging.core.MessageTypeResolver} will derive the name based
     * on the payload type (thus the <b>first</b> parameter) of the annotated method.
     *
     * @return the name of the event this handler listens to, matching to the {@link MessageType#name()} from the
     * {@link EventMessage#type()}
     * @deprecated in favor of {@link #event()}, which describes the namespace and local name of an event separately
     * and allows a handler to listen to several event names
     */
    @Deprecated(since = "5.5.0", forRemoval = true)
    String eventName() default "";

    /**
     * The names of the events this handler listens to, each described through its separate
     * {@link QualifiedName#namespace() namespace} and {@link QualifiedName#name() local name}, mirroring the attributes
     * of the {@link Event @Event} annotation.
     * <p>
     * An empty {@link QualifiedName#namespace()} or {@link QualifiedName#name()} defaults to the respective part of the
     * {@link org.axonframework.messaging.core.QualifiedName} the payload type (thus the <b>first</b> parameter) of the
     * annotated method resolves to through the {@link org.axonframework.messaging.core.MessageTypeResolver}. Thus, only
     * the parts that differ from the payload type's name have to be defined. Note that the payload type's name is split
     * into a namespace and local name at its <b>last</b> dot. For example, given a payload type annotated with
     * {@code @Event(namespace = "orders", name = "v2.OrderPlaced")}, the declaration
     * {@code @QualifiedName(namespace = "legacy")} resolves to {@code legacy.OrderPlaced}, not to
     * {@code legacy.v2.OrderPlaced}. Event names always consist of a namespace and a local name.
     * <p>
     * When neither this attribute nor the deprecated {@link #eventName()} is defined, the {@code MessageTypeResolver}
     * will derive the name based on the payload type of the annotated method. All listed events are converted to the
     * payload type of the annotated method.
     * <p>
     * Example, handling both the current and a legacy name of an event:
     * <pre>{@code
     * @Event(namespace = "com.acme")
     * public record OrderPlacedEvent(String id) { }
     *
     * @EventHandler(event = {
     *         @QualifiedName(namespace = "com.acme", name = "OrderPlacedEvent"),
     *         @QualifiedName(namespace = "com.acme.legacy", name = "OrderPlaced")
     * })
     * void on(OrderPlacedEvent event) { ... }
     * }</pre>
     *
     * @return the names of the events this handler listens to, as namespace and local name pairs
     * @since 5.5.0
     */
    QualifiedName[] event() default {};

    /**
     * The representation of the event this method requires. This is an indication for the framework to convert the
     * actual payload representation as the message is delivered with, to the configured representation.
     * <p>
     * Optional. If unspecified, the first parameter of the method defines the expected payload representation.
     *
     * @return The type of the event this method handles.
     */
    Class<?> payloadType() default Object.class;
}
