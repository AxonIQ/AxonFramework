package testing.basictesting.fixtures;

import org.axonframework.eventsourcing.annotation.EventTag;

/**
 * Shared domain fixture reused across the basic-testing.adoc samples. Not shown in the rendered documentation.
 */
public record MoneyWithdrawnEvent(@EventTag String accountId, double amount) {

}
