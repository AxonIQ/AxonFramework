package testing.basictesting.given.singlecommand;

import testing.basictesting.fixtures.CreateAccountCommand;
import testing.basictesting.fixtures.DepositMoneyCommand;

// tag::given-single-command[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .command(new CreateAccountCommand("account-1", 500.00))
               .command(new DepositMoneyCommand("account-1", 100.00));
               // when...
    }
}
// end::given-single-command[]
