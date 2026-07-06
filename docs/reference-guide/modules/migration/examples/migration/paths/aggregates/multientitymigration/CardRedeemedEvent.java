package migration.paths.aggregates.multientitymigration;

import org.axonframework.eventsourcing.annotation.EventTag;

record CardRedeemedEvent(@EventTag String cardId, int amount, String transactionId) {
}
