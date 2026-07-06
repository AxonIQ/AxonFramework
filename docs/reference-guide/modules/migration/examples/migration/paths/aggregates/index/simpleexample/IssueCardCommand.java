package migration.paths.aggregates.index.simpleexample;

import org.axonframework.modelling.annotation.TargetEntityId;

public record IssueCardCommand(@TargetEntityId String cardId, int amount) {
}
