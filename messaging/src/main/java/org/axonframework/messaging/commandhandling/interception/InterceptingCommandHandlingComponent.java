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

package org.axonframework.messaging.commandhandling.interception;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link CommandHandlingComponent} that applies a list of {@link MessageHandlerInterceptor interceptors} around every
 * command before delegating to the underlying component.
 * <p>
 * Interceptors are invoked in registration order. Each interceptor can inspect the command, modify it, short-circuit
 * handling, or proceed to the next interceptor in the chain.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
@Internal
public class InterceptingCommandHandlingComponent implements CommandHandlingComponent {

    private final CommandHandlingComponent delegate;
    private final List<MessageHandlerInterceptor<? super CommandMessage>> interceptors;
    private final CommandMessageHandlerInterceptorChain interceptorChain;

    /**
     * Constructs a new {@code InterceptingCommandHandlingComponent} wrapping the given {@code delegate} with the given
     * {@code interceptors}.
     *
     * @param interceptors the interceptors to apply, in invocation order
     * @param delegate     the underlying component to delegate to after all interceptors have proceeded
     */
    public InterceptingCommandHandlingComponent(
            List<MessageHandlerInterceptor<? super CommandMessage>> interceptors,
            CommandHandlingComponent delegate
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.interceptors = new ArrayList<>(
                Objects.requireNonNull(interceptors, "interceptors must not be null")
        );
        this.interceptorChain = new CommandMessageHandlerInterceptorChain(interceptors, delegate);
    }

    @Override
    public MessageStream.Single<CommandResultMessage> handle(CommandMessage command,
                                                            ProcessingContext processingContext) {
        return interceptorChain.proceed(command, processingContext).first().cast();
    }

    @Override
    public Set<QualifiedName> supportedCommands() {
        return delegate.supportedCommands();
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("interceptors", interceptors);
    }
}
