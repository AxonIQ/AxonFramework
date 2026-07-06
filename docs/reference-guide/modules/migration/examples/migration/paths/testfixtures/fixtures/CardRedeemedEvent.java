package migration.paths.testfixtures.fixtures;

import org.axonframework.eventsourcing.annotation.EventTag;

/**
 * Shared domain fixture reused across the test-fixtures.adoc samples. Not shown in the rendered documentation.
 */
public record CardRedeemedEvent(@EventTag String cardId, int amount) {

}
