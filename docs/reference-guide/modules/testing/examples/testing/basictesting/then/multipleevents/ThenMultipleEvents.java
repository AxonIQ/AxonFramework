package testing.basictesting.then.multipleevents;

import testing.basictesting.fixtures.BalanceUpdatedEvent;
import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::then-multiple-events[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .command(new WithdrawMoneyCommand("account-1", 100.00))
               .then()
               .events(
                       new MoneyWithdrawnEvent("account-1", 100.00),
                       new BalanceUpdatedEvent("account-1", 400.00)
               );
    }
}
// end::then-multiple-events[]
