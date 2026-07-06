package migration.paths.aggregates.index.simpleexample;

import org.axonframework.modelling.annotation.TargetEntityId;

public record RedeemCardCommand(@TargetEntityId String cardId, int amount) {
}
