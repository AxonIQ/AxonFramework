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

package org.axonframework.messaging.core.interception.annotation;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.annotation.MessageHandler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation marking a handler method as an interceptor handler. Unlike regular handlers, interceptor handlers are
 * chained and do not block processing by other handlers. They can be used to add processing behavior either before,
 * after or both before and after processing of other handlers.
 * <p>
 * When parameters of an interceptor do not match the message, this will prevent the invocation of this handler, but it
 * will not block processing of any other handlers that do match.
 *
 * @author Allard Buijze
 * @since 4.4.0
 */
@MessageHandler
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface MessageHandlerInterceptor {

    /**
     * Specifies the type of message that can be handled by the member method. Defaults to any {@link Message}.
     *
     * @return The type of {@link Message} handled by function annotated with {@code @MessageHandlerInterceptor}.
     */
    Class<? extends Message> messageType() default Message.class;

    /**
     * Specifies the type of message payload that can be handled by the member method. The payload of the message should
     * be assignable to this type. Defaults to any {@link Object}.
     *
     * @return The type of {@link Message#payload()} handled by the function annotated with
     * {@code @MessageHandlerInterceptor}.
     */
    Class<?> payloadType() default Object.class;
}
