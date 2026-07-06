package migration.paths.testfixtures.fixtures;

import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Shared domain fixture reused across the test-fixtures.adoc samples. Not shown in the rendered documentation.
 */
public record RedeemCardCommand(@TargetEntityId String cardId, int amount) {

}
