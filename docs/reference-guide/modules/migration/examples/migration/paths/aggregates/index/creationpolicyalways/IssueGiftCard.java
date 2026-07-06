package migration.paths.aggregates.index.creationpolicyalways;

import org.axonframework.modelling.annotation.TargetEntityId;

public record IssueGiftCard(@TargetEntityId String cardId, int amount) {
}
