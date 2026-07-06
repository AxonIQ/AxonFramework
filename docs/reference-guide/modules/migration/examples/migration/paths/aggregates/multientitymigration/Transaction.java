package migration.paths.aggregates.multientitymigration;

// tag::gift-card-transaction[]
public class Transaction {

    private String transactionId; // <3>
    private int amount;

    public Transaction(String transactionId, int amount) {
        this.transactionId = transactionId;
        this.amount = amount;
    }
}
// end::gift-card-transaction[]
