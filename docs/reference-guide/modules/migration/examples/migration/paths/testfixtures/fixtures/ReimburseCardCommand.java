package migration.paths.testfixtures.fixtures;

import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Shared domain fixture reused across the test-fixtures.adoc samples. Not shown in the rendered documentation.
 */
public record ReimburseCardCommand(@TargetEntityId String cardId, int amount) {

}
