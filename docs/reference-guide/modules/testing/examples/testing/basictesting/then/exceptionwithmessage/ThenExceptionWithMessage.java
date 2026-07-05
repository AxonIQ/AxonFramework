package testing.basictesting.then.exceptionwithmessage;

import testing.basictesting.fixtures.InsufficientBalanceException;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::then-exception-with-message[]
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
               .exception(InsufficientBalanceException.class, "Insufficient balance");
    }
}
// end::then-exception-with-message[]
