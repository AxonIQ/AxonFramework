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

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that marks a method in: - an Aggregate (either the root, or an entity) as a handler for Events generated
 * by that aggregate. - a State model needed for {@link CommandHandler} as
 * a handler for Events sourced in order to handle given {@link CommandMessage}. The
 * parameters of the annotated method are resolved using parameter resolvers.
 * <p/>
 * Axon provides a number of parameter resolvers that allow you to use the following parameter types:<ul>
 * <li>The first parameter is always the payload of the Event message, unless the {@code payloadType} parameter is
 * provided. In that case, the first parameter any other type of parameter described below.</li>
 * <li>Parameters annotated with {@code @MetadataValue} will resolve to the metadata value with the key as indicated
 * on the annotation. If required is false (default), null is passed when the metadata value is not present. If required
 * is true, the resolver will not match and prevent the method from being invoked when the metadata value is not
 * present.</li>
 * <li>Parameters of type {@link Metadata} will have the entire metadata of an Event Message
 * injected.</li>
 * <li>Parameters of type {@link java.time.Instant} annotated with
 * {@link org.axonframework.messaging.eventhandling.annotation.Timestamp @Timestamp} will resolve to the timestamp of
 * the EventMessage. This is the time at which the Event was generated.</li>
 * <li>Parameters assignable to {@link Message} will have the entire {@link
 * EventMessage} injected (if the message is assignable to that parameter). If the first
 * parameter is of type message, it effectively matches an Event of any type, even if generic parameters would suggest
 * otherwise. Due to type erasure, Axon cannot detect what parameter is expected. In such case, it is best to declare a
 * parameter of the payload type, followed by a parameter of type Message.</li>
 * <li>When using Spring and {@code &lt;axon:annotation-config/&gt;} is declared, any other parameters will
 * resolve to autowired beans, if exactly one autowire candidate is available in the application context. This allows
 * you to inject resources directly into {@code @EventSourcingHandler} annotated methods.</li>
 * </ul>
 * <p/>
 * An overriding method counts as one handler and runs where the handler it overrides runs; call {@code super} to run
 * the overridden one. Separate methods for the same event all run, parent handlers first, each receiving the entity
 * returned by the previous one. Within one class the order is unspecified. If a handler returns an entity the next
 * handler cannot run on, evolving fails with a {@link org.axonframework.modelling.StateEvolvingException}. Handlers of
 * the returned type do not run for the same event.
 * <p/>
 * A handler matches an event by its event name, so a handler accepting {@code Object} does not receive other events.
 * <p/>
 * The annotation may be repeated on a method. Each occurrence registers the method as a separate handler, for example
 * to keep sourcing events that were published under a previous name:
 * <pre>{@code
 * @EventSourcingHandler(namespace = "billing", name = "EnrollmentPaid")
 * @EventSourcingHandler(namespace = "billing", name = "EnrollmentSettled")
 * void on(PaymentReceived event) {
 *     this.paid = true;
 * }
 * }</pre>
 * Declared names replace the name the payload type resolves to, instead of adding to it. To source that name as well,
 * add an {@code @EventSourcingHandler} without attributes.
 *
 * @author Allard Buijze
 * @since 2.1.0
 */
@EventHandler
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Repeatable(EventSourcingHandlers.class)
public @interface EventSourcingHandler {

    /**
     * The name of the event this method handles, matching the
     * {@link org.axonframework.messaging.core.MessageType#name()} of the {@link EventMessage#type()}.
     * <p>
     * Use it when the event is published under a name that differs from the name of the handler's payload type. For
     * example, an event another module publishes as {@code billing.EnrollmentPaid}, read here through a local class:
     * <pre>{@code
     * @EventSourcingHandler(eventName = "billing.EnrollmentPaid")
     * void on(PaymentReceived event) {
     *     this.paid = true;
     * }
     * }</pre>
     * The payload is converted to the type of the first parameter before the handler is invoked.
     * <p>
     * Optional. If unspecified, the {@link org.axonframework.messaging.core.MessageTypeResolver} derives the name from
     * the payload type, thus the first parameter of the method. Mirrors {@link EventHandler#eventName()}.
     *
     * @return the name of the event this method handles, or an empty {@code String} to derive it from the payload type
     * @since 5.5.0
     */
    String eventName() default "";

    /**
     * The namespace of the event this method handles, matching the
     * {@link org.axonframework.messaging.core.QualifiedName#namespace()} of the {@link EventMessage#type()}.
     * <p>
     * Optional. If unspecified, the namespace of the name the payload type, thus the first parameter of the method,
     * resolves to is used. Cannot be combined with {@link #eventName()}. Mirrors {@link EventHandler#namespace()}.
     *
     * @return the namespace of the event this method handles, or an empty {@code String} to derive it from the payload
     * type
     * @since 5.5.0
     */
    String namespace() default "";

    /**
     * The local name of the event this method handles, matching the
     * {@link org.axonframework.messaging.core.QualifiedName#localName()} of the {@link EventMessage#type()}.
     * <p>
     * Optional. If unspecified, the local name of the name the payload type, thus the first parameter of the method,
     * resolves to is used. Cannot be combined with {@link #eventName()}. Mirrors {@link EventHandler#name()}.
     *
     * @return the local name of the event this method handles, or an empty {@code String} to derive it from the
     * payload type
     * @since 5.5.0
     */
    String name() default "";

    /**
     * The type of event this method handles. If specified, this handler will only be invoked for message that have a
     * payload assignable to the given payload type.
     * <p>
     * Optional. If unspecified, the first parameter of the method defines the type of supported event.
     *
     * @return The type of the event this method handles.
     */
    Class<?> payloadType() default Object.class;
}
