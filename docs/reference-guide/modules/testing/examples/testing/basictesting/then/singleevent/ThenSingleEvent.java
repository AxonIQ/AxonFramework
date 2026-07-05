package testing.basictesting.then.singleevent;

import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::then-single-event[]
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
               .events(new MoneyWithdrawnEvent("account-1", 100.00));
    }
}
// end::then-single-event[]
