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

package org.axonframework.deadline.annotation;

import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.messaging.core.annotation.MessageHandler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used to mark handlers which are capable of handling a {@link DeadlineMessage}. It is a specialization of
 * {@link MessageHandler} were the {@code messageType} is set to DeadlineMessage. Hence, any parameter injections which
 * works for event handlers work for deadline handlers as well.
 *
 * @author Milan Savic
 * @author Steven van Beelen
 * @see MessageHandler
 * @since 3.3.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@MessageHandler(messageType = DeadlineMessage.class)
public @interface DeadlineHandler {

    /**
     * The name of the Deadline this handler listens to. Defaults to the fully qualified class name of the payload type
     * (i.e. first parameter).
     *
     * @return The name of the deadline as a {@link String}
     */
    String deadlineName() default "";

    /**
     * Specifies the type of message payload that can be handled by the member method. The payload of the message should
     * be assignable to this type. Defaults to any {@link Object}.
     *
     * @return The payload type handled by the function annotated with {@code @DeadlineHandler}.
     */
    Class<?> payloadType() default Object.class;
}
