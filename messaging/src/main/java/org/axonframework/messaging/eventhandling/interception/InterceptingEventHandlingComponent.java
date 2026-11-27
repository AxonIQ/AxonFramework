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

package org.axonframework.messaging.eventhandling.interception;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.common.configuration.DecoratorOrder;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.DelegatingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An {@link EventHandlingComponent} implementation that supports intercepting event handling through
 * MessageHandlerInterceptors. This component delegates actual event handling to another {@link EventHandlingComponent}
 * while applying configured interceptors to the message handling process.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
@Internal
public class InterceptingEventHandlingComponent extends DelegatingEventHandlingComponent {

    /**
     * The order in which the {@link InterceptingEventHandlingComponent} is applied as a
     * {@link ComponentRegistry#registerDecorator(DecoratorDefinition) decorator} to an {@link EventHandlingComponent}.
     * <p>
     * As such, any decorator with a lower value will be applied to the delegate, and any higher value will be applied
     * to the {@code InterceptingEventHandlingComponent} itself. Using the same value can either lead to application of
     * the decorator to the delegate or the {@code InterceptingEventHandlingComponent}, depending on the order of
     * registration.
     * <p>
     * The order of the {@code InterceptingEventHandlingComponent} is set to {@code Integer.MIN_VALUE + 100} to ensure
     * it is applied very early in the configuration process, but not the earliest to allow for other decorators to be
     * applied.
     *
     * @deprecated in favor of {@link DecoratorOrder#INTERCEPTING}
     */
    @Deprecated(since = "5.5.0")
    public static final int DECORATION_ORDER = DecoratorOrder.INTERCEPTING;

    private final List<MessageHandlerInterceptor<? super EventMessage>> interceptors;
    private final EventMessageHandlerInterceptorChain interceptorChain;

    /**
     * Constructs the component with the given delegate and interceptors.
     *
     * @param delegate     the EventHandlingComponent to delegate to
     * @param interceptors the list of interceptors to initialize with
     */
    public InterceptingEventHandlingComponent(
            List<MessageHandlerInterceptor<? super EventMessage>> interceptors,
            EventHandlingComponent delegate
    ) {
        super(delegate);
        this.interceptors = new ArrayList<>(Objects.requireNonNull(interceptors, "The interceptors must not be null."));
        this.interceptorChain = new EventMessageHandlerInterceptorChain(interceptors, delegate);
    }

    @Override
    public MessageStream.Empty<Message> handle(EventMessage event,
                                               ProcessingContext context) {
        return interceptorChain.proceed(event, context)
                               .ignoreEntries()
                               .cast();
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        super.describeTo(descriptor);
        descriptor.describeProperty("interceptors", interceptors);
    }
}
