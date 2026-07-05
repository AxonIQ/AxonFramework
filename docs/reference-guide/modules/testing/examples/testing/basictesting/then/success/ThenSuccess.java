package testing.basictesting.then.success;

import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::then-success[]
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
               .success();
    }
}
// end::then-success[]
