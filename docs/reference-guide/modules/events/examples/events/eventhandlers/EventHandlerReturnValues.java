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
package events.eventhandlers;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;

import java.util.concurrent.CompletableFuture;

import reactor.core.publisher.Mono;

// tag::event-handler-void-return[]
class VoidReturnHandler {

    @EventHandler
    public void on(OrderPlacedEvent event) {
        // Update state, no return value
    }
}
// end::event-handler-void-return[]

// tag::event-handler-completablefuture-return[]
class CompletableFutureReturnHandler {

    private AsyncService asyncService;

    @EventHandler
    public CompletableFuture<Void> on(OrderPlacedEvent event) {
        return asyncService.processOrder(event.orderId());
    }
}
// end::event-handler-completablefuture-return[]

// tag::event-handler-mono-return[]
class MonoReturnHandler {

    private ReactiveService reactiveService;

    @EventHandler
    public Mono<Void> on(OrderPlacedEvent event) {
        return reactiveService.processOrder(event.orderId());
    }
}
// end::event-handler-mono-return[]

interface AsyncService {

    CompletableFuture<Void> processOrder(String orderId);
}

interface ReactiveService {

    Mono<Void> processOrder(String orderId);
}
