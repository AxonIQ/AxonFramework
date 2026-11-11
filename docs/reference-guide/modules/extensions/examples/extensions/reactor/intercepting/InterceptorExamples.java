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

package extensions.reactor.intercepting;

// tag::metadata-interceptor[]

import org.axonframework.extension.reactor.messaging.core.ReactorMessageDispatchInterceptor;
import org.axonframework.extension.reactor.messaging.core.ReactorMessageDispatchInterceptorChain;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

class MetadataEnrichingInterceptor implements ReactorMessageDispatchInterceptor<CommandMessage> {

    @Override
    public Mono<?> interceptOnDispatch(CommandMessage message,
                                       @Nullable ProcessingContext context,
                                       ReactorMessageDispatchInterceptorChain<CommandMessage> chain) {
        return chain.proceed(message.andMetadata(Metadata.with("key1", "value1")), context);
    }
}
// end::metadata-interceptor[]

// tag::security-interceptor[]
class SecurityFlagInterceptor implements ReactorMessageDispatchInterceptor<CommandMessage> {

    @Override
    public Mono<?> interceptOnDispatch(CommandMessage message,
                                       @Nullable ProcessingContext context,
                                       ReactorMessageDispatchInterceptorChain<CommandMessage> chain) {
        return Mono.deferContextual(
                reactorContext -> reactorContext.hasKey("security")
                        ? chain.proceed(message, context)
                        : Mono.empty() // discards the message
        );
    }
}
// end::security-interceptor[]
