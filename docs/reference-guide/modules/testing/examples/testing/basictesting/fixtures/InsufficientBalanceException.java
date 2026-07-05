package testing.basictesting.fixtures;

/**
 * Shared domain fixture reused across the basic-testing.adoc samples. Not shown in the rendered documentation.
 */
public class InsufficientBalanceException extends RuntimeException {

    private final String accountId;
    private final double requestedAmount;

    public InsufficientBalanceException(String accountId, double requestedAmount) {
        super("Insufficient balance on account " + accountId + " for requested amount " + requestedAmount);
        this.accountId = accountId;
        this.requestedAmount = requestedAmount;
    }

    public String accountId() {
        return accountId;
    }

    public double requestedAmount() {
        return requestedAmount;
    }
}
