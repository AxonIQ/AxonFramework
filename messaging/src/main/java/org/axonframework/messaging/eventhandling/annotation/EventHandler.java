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
     * When not defined the {@link org.axonframework.messaging.core.MessageTypeResolver} will derive the name based on
     * the payload type (thus the <b>first</b> parameter) of the annotated method.
     *
     * @return The name of the event this handler listens to, matching to the {@link MessageType#name()} from the
     * {@link EventMessage#type()}.
     */
    String eventName() default "";

    /**
     * The representation of the event this method requires. This is an indication for the framework to convert the
     * actual payload representation as the message is delivered with, to the configured representation.
     * <p>
     * Optional. If unspecified, the first parameter of the method defines the expected payload representation.
     *
     * @return The type of the event this method handles.
     */
    Class<?> payloadType() default Object.class;

    /**
     * The version range of the event this handler listens to.
     * <p>
     * Defaults to an empty string, meaning the handler accepts any version of the event.
     * The string can define a specific version (e.g. {@code "1.0"}) or an inclusive range (e.g. {@code "1.0-2.0"}).
     *
     * @return The version range of the event expected by this handler.
     */
    String versionRange() default "";
}
