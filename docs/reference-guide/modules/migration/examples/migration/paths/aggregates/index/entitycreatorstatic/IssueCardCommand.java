package migration.paths.aggregates.index.entitycreatorstatic;

import org.axonframework.modelling.annotation.TargetEntityId;

public record IssueCardCommand(@TargetEntityId String cardId, int amount) {
}
