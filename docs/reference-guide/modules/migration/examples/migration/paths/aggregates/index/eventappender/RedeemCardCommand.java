package migration.paths.aggregates.index.eventappender;

import org.axonframework.modelling.annotation.TargetEntityId;

public record RedeemCardCommand(@TargetEntityId String cardId, int amount) {
}
