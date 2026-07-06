package migration.paths.aggregates.index.creationpolicycreateifmissing;

import org.axonframework.modelling.annotation.TargetEntityId;

public record IssueGiftCard(@TargetEntityId String cardId, int amount) {
}
