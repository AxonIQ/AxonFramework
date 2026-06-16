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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation marking a Handler as an interceptor for other handlers that is only interested in handling
 * exception results. This handler method will be invoked after a regular handler has been executed and may receive the
 * result of that handler as a parameter.
 * <p>
 * A handler will only be invoked when the parameters of this method match the combination of the handled Message and
 * the result of the handler method invocation.
 *
 * @author Allard Buijze
 * @since 4.4.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@MessageHandlerInterceptor
@ResultHandler(resultType = Exception.class)
public @interface ExceptionHandler {

    /**
     * Defines the type of result this handler needs to be triggered for. Defaults to all {@code Exception}s.
     */
    Class<? extends Exception> resultType() default Exception.class;

    /**
     * Specifies the type of message that can be handled by the member method. Defaults to any {@link Message}.
     */
    Class<? extends Message> messageType() default Message.class;

}
