package testing.basictesting.when.command;

import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::when-command[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .command(new WithdrawMoneyCommand("account-1", 100.00));
               // then...
    }
}
// end::when-command[]
