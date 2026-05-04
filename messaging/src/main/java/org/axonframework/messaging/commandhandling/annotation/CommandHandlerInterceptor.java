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
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation marking a method as a command-specific interceptor handler on an
 * {@link AnnotatedCommandHandlingComponent}.
 * <p>
 * An annotated interceptor is invoked before every {@link CommandHandler @CommandHandler} method on the same component
 * instance. Two styles are supported:
 * <ul>
 *   <li><strong>Before-interceptor</strong> — a method with no
 *       {@link org.axonframework.messaging.core.MessageHandlerInterceptorChain} parameter that returns either
 *       {@code void} or {@code CompletableFuture<Void>}. The method runs before the handler; the chain is
 *       automatically proceeded after the method returns (or the future completes) normally. If it throws (or the
 *       future completes exceptionally), the handler is not invoked.</li>
 *   <li><strong>Surround-interceptor</strong> — a method that declares a
 *       {@link org.axonframework.messaging.core.MessageHandlerInterceptorChain} parameter and returns a
 *       {@link org.axonframework.messaging.core.MessageStream}. The method controls whether and when the chain is
 *       proceeded by calling {@code chain.proceed(command, ctx)}; it can also short-circuit by returning without
 *       calling proceed.</li>
 * </ul>
 * <p>
 * The {@link #payloadType()} attribute narrows the interceptor to commands whose payload is assignable to that type.
 * When not specified, the interceptor applies to all commands handled by the component.
 * <p>
 * Example before-interceptor:
 * <pre>{@code
 * @CommandHandlerInterceptor
 * void audit(CommandMessage<?> command) {
 *     auditLog.record(command.qualifiedName());
 * }
 * }</pre>
 * <p>
 * Example surround-interceptor with short-circuit:
 * <pre>{@code
 * @CommandHandlerInterceptor
 * MessageStream<?> filterByTenant(
 *         CommandMessage<?> command,
 *         MessageHandlerInterceptorChain<CommandMessage<?>> chain,
 *         ProcessingContext ctx
 * ) {
 *     if (!tenantId.equals(command.metaData().get("tenantId"))) {
 *         return MessageStream.failed(new AccessDeniedException("Wrong tenant"));
 *     }
 *     return chain.proceed(command, ctx);
 * }
 * }</pre>
 *
 * @author Allard Buijze
 * @see MessageHandlerInterceptor
 * @see AnnotatedCommandHandlingComponent
 * @since 5.2.0
 */
@MessageHandlerInterceptor(messageType = CommandMessage.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface CommandHandlerInterceptor {

    /**
     * The payload type to narrow this interceptor to; only commands whose payload is assignable to this type will
     * trigger the interceptor. Defaults to any payload.
     *
     * @return the payload type this interceptor applies to
     */
    Class<?> payloadType() default Object.class;
}
