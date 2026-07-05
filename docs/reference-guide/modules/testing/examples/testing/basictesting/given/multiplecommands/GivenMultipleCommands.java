package testing.basictesting.given.multiplecommands;

import testing.basictesting.fixtures.CreateAccountCommand;
import testing.basictesting.fixtures.DepositMoneyCommand;

// tag::given-multiple-commands[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .commands(
                       new CreateAccountCommand("account-1", 500.00),
                       new DepositMoneyCommand("account-1", 100.00)
               );
               // when...
    }
}
// end::given-multiple-commands[]
