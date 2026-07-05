package testing.matchersandfieldfilters.fieldfilters;

// tag::account-created-event[]
public record AccountCreatedEvent(
    String accountId,
    double initialBalance
) {
}
// end::account-created-event[]
