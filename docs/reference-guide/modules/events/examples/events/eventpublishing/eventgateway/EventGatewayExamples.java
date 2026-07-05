package events.eventpublishing.eventgateway;

// tag::publish-events-with-eventgateway-import[]
import org.axonframework.messaging.eventhandling.gateway.EventGateway;

import java.util.List;
import java.util.concurrent.CompletableFuture;

// end::publish-events-with-eventgateway-import[]
// tag::publish-events-without-context-import[]
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

// end::publish-events-without-context-import[]
// The two imports below are indented to the depth of the nested method further
// down, so that indent=0 normalization of the include renders both regions of
// that snippet flush left.
// tag::publish-events-with-context-import[]
    import org.axonframework.messaging.core.unitofwork.ProcessingContext;
    import org.axonframework.messaging.eventhandling.annotation.EventHandler;
    import org.axonframework.messaging.eventhandling.gateway.EventGateway;

// end::publish-events-with-context-import[]

// tag::publish-events-with-eventgateway-class[]
class Notifier {

    private EventGateway eventGateway;

    public CompletableFuture<Void> publishEvent() {
        return eventGateway.publish(List.of(new CardIssuedEvent("card-1", 100, "Axoniq shop")));
    }
}
// end::publish-events-with-eventgateway-class[]

record CardIssuedEvent(String cardId, int amount, String shopId) {

}

// tag::publish-events-without-context-class[]
@RestController
class CardController {

    private final EventGateway eventGateway;

    public CardController(EventGateway eventGateway) {
        this.eventGateway = eventGateway;
    }

    @PostMapping("/cards/{cardId}/issue")
    public CompletableFuture<Void> issueCard(@PathVariable String cardId,
                                             @RequestBody IssueCardRequest request) {
        return eventGateway.publish(List.of(
                new CardIssuedEvent(cardId, request.amount(), request.shopId()))
        );
    }
}
// end::publish-events-without-context-class[]

record IssueCardRequest(int amount, String shopId) {

}

class PaymentEventHandler {

    // tag::publish-events-with-context[]
    @EventHandler
    public void on(PaymentReceivedEvent event,
                   ProcessingContext context,
                   EventGateway eventGateway) {
        // Validation logic...

        eventGateway.publish(
            context,
            new BalanceUpdatedEvent(event.getAccountId(), event.getAmount())
        );
    }
    // end::publish-events-with-context[]
}

class PaymentReceivedEvent {

    private final String accountId;
    private final int amount;

    PaymentReceivedEvent(String accountId, int amount) {
        this.accountId = accountId;
        this.amount = amount;
    }

    public String getAccountId() {
        return accountId;
    }

    public int getAmount() {
        return amount;
    }
}

record BalanceUpdatedEvent(String accountId, int amount) {

}

class NotifierWithLogging {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(NotifierWithLogging.class);

    private EventGateway eventGateway;

    // tag::publish-event-async-chaining[]
    public CompletableFuture<Void> publishEvent() {
        return eventGateway.publish(List.of(new CardIssuedEvent("card-1", 100, "Axoniq shop")))
                           .thenRun(() -> logger.info("Event published successfully"))
                           .exceptionally(ex -> {
                               logger.error("Failed to publish event", ex);
                               return null;
                           });
    }
    // end::publish-event-async-chaining[]
}
