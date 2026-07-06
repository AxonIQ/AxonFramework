package migration.paths.aggregates.multientitymigration;

import org.axonframework.modelling.annotation.TargetEntityId;

record RedeemCardCommand(@TargetEntityId String cardId, int amount, String transactionId) {
}
