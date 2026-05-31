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

package org.axonframework.extension.micronaut.authorization;

import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.utils.SecurityService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.conversion.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link MessageDispatchInterceptor} that adds the {@code username} and {@code authorities} from the authorized
 * principle.
 *
 * @param <T> The message type this interceptor can process
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@Singleton
public class MessageAuthorizationDispatchInterceptor<T extends Message> implements MessageDispatchInterceptor<T> {

    private static final Logger logger = LoggerFactory.getLogger(MessageAuthorizationDispatchInterceptor.class);

    private final Converter converter;
    private final SecurityService securityService;

    /**
     * Construct a {@code MessageAuthorizationDispatchInterceptor} using the given {@code converter} to convert the
     * {@link Authentication#getName()} and {@link Authentication#getRoles()} before they are
     * stored in the {@link Metadata} of the outgoing {@link Message} of type {@code T}.
     *
     * @param converter The {@code Converter} converting the {@link Authentication#getRoles()} and
     *                  {@link Authentication#getName()} before they go into the
     *                  {@link Metadata} of the outgoing {@link Message} of type {@code T}.
     * @param securityService The SecurityService for retrieving the authentication from the current context
     */
    public MessageAuthorizationDispatchInterceptor(@Nonnull Converter converter, @Nonnull SecurityService securityService) {
        this.converter = Objects.requireNonNull(converter, "Converter must not be null.");
        this.securityService = securityService;
    }

    @Nonnull
    @Override
    public MessageStream<?> interceptOnDispatch(@Nonnull T message,
                                                @Nullable ProcessingContext context,
                                                @Nonnull MessageDispatchInterceptorChain<T> interceptorChain) {

        Optional<Authentication> authentication = securityService.getAuthentication();
        if (authentication.isEmpty()) {
            logger.debug("No authentication found.");
            return interceptorChain.proceed(message, context);
        }

        logger.debug("Adding message metadata for username & authorities.");
        Map<String, String> authenticationDetails = new java.util.HashMap<>();
        authenticationDetails.put("username", converter.convert(authentication.get().getName(), String.class));
        String authorities = String.join(",", authentication.get().getRoles());
        authenticationDetails.put("authorities", authorities);
        //noinspection unchecked
        return interceptorChain.proceed((T) message.andMetadata(authenticationDetails), context);
    }
}
