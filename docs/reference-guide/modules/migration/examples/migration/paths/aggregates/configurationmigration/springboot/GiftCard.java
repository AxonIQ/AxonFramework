package migration.paths.aggregates.configurationmigration.springboot;

import org.axonframework.extension.spring.stereotype.EventSourced;

// tag::event-sourced-spring[]
// Axon Framework 5
@EventSourced
public class GiftCard {
    private String giftCardId;
    // ...
}
// end::event-sourced-spring[]
