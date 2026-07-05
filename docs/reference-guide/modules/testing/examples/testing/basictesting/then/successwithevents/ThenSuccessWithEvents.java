package testing.basictesting.then.successwithevents;

import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::then-success-with-events[]
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
               .success()
               .events(new MoneyWithdrawnEvent("account-1", 100.00));
    }
}
// end::then-success-with-events[]
