package testing.basictesting.fixtures;

import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Shared domain fixture reused across the basic-testing.adoc samples. Not shown in the rendered documentation.
 */
public record DepositMoneyCommand(@TargetEntityId String accountId, double amount) {

}
