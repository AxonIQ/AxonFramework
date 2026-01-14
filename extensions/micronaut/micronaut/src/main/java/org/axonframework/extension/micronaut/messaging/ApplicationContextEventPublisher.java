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

package org.axonframework.extension.micronaut.messaging;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.annotation.PostConstruct;
import org.axonframework.messaging.core.SubscribableEventSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Component that forward events received from a {@link SubscribableEventSource} to Micronauts ApplicationContext.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@Context
@Requires(bean = SubscribableEventSource.class)
public class ApplicationContextEventPublisher {

    private final SubscribableEventSource messageSource;
    private final ApplicationEventPublisher<Object> applicationEventPublisher;

    /**
     * Initialize the publisher to forward events received from the given {@code messageSource} to the application
     * context that this bean is part of.
     *
     * @param messageSource             The source to subscribe to.
     * @param applicationEventPublisher The ApplicationEventPublisher to publish events with
     */
    public ApplicationContextEventPublisher(SubscribableEventSource messageSource,
                                            ApplicationEventPublisher<Object> applicationEventPublisher) {
        this.messageSource = messageSource;
        this.applicationEventPublisher = applicationEventPublisher;
    }


    /**
     * subscribes to the message source, and publish all event asynchronously
     */
    @PostConstruct
    public void postConstruct() {
        messageSource.subscribe((msgs, ctx) -> {
            msgs.stream()
                .map(msg -> applicationEventPublisher.publishEventAsync(msg.payload()))
                .forEach(future -> {
                    try {
                        future.get();
                    } catch (InterruptedException ignored) {
                    } catch (ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                });
            return CompletableFuture.completedFuture(null);
        });
    }
}
