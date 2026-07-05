package testing.basictesting.fixtures;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

/**
 * Shared domain fixture reused across the basic-testing.adoc samples. Not shown in the rendered documentation.
 * <p>
 * A purely event-sourced entity, used in the samples that register command handling separately (either as a
 * dedicated stateful command handling component, or via an entity subclass that adds its own command handlers).
 */
@EventSourcedEntity(tagKey = "accountId")
public class Account {

    private String accountId;
    private double balance;
    private boolean closed;

    @EntityCreator
    public Account() {
    }

    @EventSourcingHandler
    void on(AccountCreatedEvent event) {
        this.accountId = event.accountId();
        this.balance = event.balance();
    }

    @EventSourcingHandler
    void on(MoneyDepositedEvent event) {
        this.balance += event.amount();
    }

    @EventSourcingHandler
    void on(MoneyWithdrawnEvent event) {
        this.balance -= event.amount();
    }

    @EventSourcingHandler
    void on(AccountClosedEvent event) {
        this.closed = true;
    }

    public String accountId() {
        return accountId;
    }

    public double balance() {
        return balance;
    }

    public boolean closed() {
        return closed;
    }
}
