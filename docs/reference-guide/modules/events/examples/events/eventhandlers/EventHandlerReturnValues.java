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
