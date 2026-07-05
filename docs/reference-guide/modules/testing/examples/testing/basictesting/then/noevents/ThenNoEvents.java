package testing.basictesting.then.noevents;

import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::then-no-events[]
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
               .noEvents();
    }
}
// end::then-no-events[]
