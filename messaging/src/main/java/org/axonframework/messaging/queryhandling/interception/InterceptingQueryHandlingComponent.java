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

package org.axonframework.messaging.queryhandling.interception;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.QueryHandlingComponent;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link QueryHandlingComponent} that applies a list of {@link MessageHandlerInterceptor interceptors} around every
 * query before delegating to the underlying component.
 * <p>
 * Interceptors are invoked in registration order. Each interceptor can inspect the query, modify it, short-circuit
 * handling, or proceed to the next interceptor in the chain.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
@Internal
public class InterceptingQueryHandlingComponent implements QueryHandlingComponent {

    private final QueryHandlingComponent delegate;
    private final List<MessageHandlerInterceptor<? super QueryMessage>> interceptors;
    private final QueryMessageHandlerInterceptorChain interceptorChain;

    /**
     * Constructs a new {@code InterceptingQueryHandlingComponent} wrapping the given {@code delegate} with the given
     * {@code interceptors}.
     *
     * @param interceptors the interceptors to apply, in invocation order
     * @param delegate     the underlying component to delegate to after all interceptors have proceeded
     */
    public InterceptingQueryHandlingComponent(
            List<MessageHandlerInterceptor<? super QueryMessage>> interceptors,
            QueryHandlingComponent delegate
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.interceptors = new ArrayList<>(
                Objects.requireNonNull(interceptors, "interceptors must not be null")
        );
        this.interceptorChain = new QueryMessageHandlerInterceptorChain(interceptors, delegate);
    }

    @Override
    public MessageStream<QueryResponseMessage> handle(QueryMessage query, ProcessingContext processingContext) {
        return interceptorChain.proceed(query, processingContext).cast();
    }

    @Override
    public Set<QualifiedName> supportedQueries() {
        return delegate.supportedQueries();
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("interceptors", interceptors);
    }
}
