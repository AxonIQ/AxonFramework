package testing.basictesting.then.exception;

import testing.basictesting.fixtures.InsufficientBalanceException;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::then-exception[]
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
               .exception(InsufficientBalanceException.class);
    }
}
// end::then-exception[]
