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
package configuration.configurer.decorator;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

class TracingCommandBusDecorator implements CommandBus {

    private final CommandBus delegate;
    private final SpanFactory spanFactory;

    TracingCommandBusDecorator(CommandBus delegate, SpanFactory spanFactory) {
        this.delegate = delegate;
        this.spanFactory = spanFactory;
    }

    @Override
    public CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                            @Nullable ProcessingContext processingContext) {
        Span span = spanFactory.createDispatchSpan(
                "CommandBus.dispatch " + command.type().qualifiedName().name(), command, processingContext
        );
        return span.branchAsync(processingContext,
                                context -> delegate.dispatch(span.propagateContext(command), context));
    }

    @Override
    public CommandBus subscribe(QualifiedName name, CommandHandler commandHandler) {
        delegate.subscribe(name, commandHandler);
        return this;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("spanFactory", spanFactory);
    }
}
