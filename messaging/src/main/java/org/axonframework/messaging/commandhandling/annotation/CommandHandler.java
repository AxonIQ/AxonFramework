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

package org.axonframework.messaging.commandhandling.annotation;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.MessageHandler;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to be placed on methods that can handle {@link CommandMessage commands}, thus making them
 * {@link org.axonframework.messaging.commandhandling.CommandHandler CommandHandlers}.
 * <p>
 * Command handler annotated methods are typically subscribed with a
 * {@link org.axonframework.messaging.commandhandling.CommandBus} as part of an
 * {@link AnnotatedCommandHandlingComponent}. This annotation can also be placed directly on entity children (read: an
 * entity contained in another entity) to have it handle the commands directly.
 * <p>
 * The parameters of the annotated method are resolved using parameter resolvers. Axon provides a number of parameter
 * resolvers that allow you to use the following parameter types:<ul>
 * <li>The first parameter is always the {@link CommandMessage#payload() payload} of the {@code CommandMessage}.
 * <li>Parameters annotated with {@link org.axonframework.messaging.core.annotation.MetadataValue} will resolve to the
 * {@link org.axonframework.messaging.core.Metadata} value with the key as indicated on the annotation. If required is
 * false (default), null is passed when the metadata value is not present. If required is true, the resolver will not
 * match and prevent the method from being invoked when the metadata value is not present.</li>
 * <li>Parameters of type {@code Metadata} will have the entire
 * {@link CommandMessage#metadata() command message metadata} injected.</li>
 * <li>Parameters assignable to {@link org.axonframework.messaging.core.Message} will have the entire {@link
 * CommandMessage} injected (if the message is assignable to that parameter). If the first parameter is of type message,
 * it effectively matches a command of any type. Due to type erasure, Axon cannot detect what parameter is expected. In
 * such case, it is best to declare a parameter of the payload type, followed by a parameter of type
 * {@code Message}.</li>
 * <li>A parameter of type {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} will inject the active
 * processing context at that moment in time.</li>
 * </ul>
 *
 * @author Allard Buijze
 * @since 0.5.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@MessageHandler(messageType = CommandMessage.class)
public @interface CommandHandler {

    /**
     * The name of the command this handler listens to, matching to the {@link MessageType#name()} from the
     * {@link CommandMessage#type()}.
     * <p>
     * When not defined the {@link org.axonframework.messaging.core.MessageTypeResolver} will derive the name based on
     * the payload type (thus the <b>first</b> parameter) of the annotated method.
     *
     * @return The name of the command this handler listens to, matching to the {@link MessageType#name()} from the
     * {@link CommandMessage#type()}.
     */
    String commandName() default "";

    /**
     * The property of the command to be used as a routing key towards this command handler instance.
     * <p>
     * If multiple handlers instances are available, a sending component is responsible to route commands with the same
     * routing key value to the correct instance.
     *
     * @return The property of the command to use as routing key.
     */
    String routingKey() default "";

    /**
     * The type of payload expected by this handler.
     * <p>
     * Defaults to the expected types expresses by (primarily the first) parameters of the annotated method.
     *
     * @return The payload type expected by this handler.
     */
    Class<?> payloadType() default Object.class;

    /**
     * The version range of the command this handler listens to.
     * <p>
     * Defaults to an empty string, meaning the handler accepts any version of the command.
     * The string can define a specific version (e.g. {@code "1.0"}) or an inclusive range (e.g. {@code "1.0-2.0"}).
     *
     * @return The version range of the command expected by this handler.
     */
    String versionRange() default "";
}
