package testing.basictesting.when.commandwithmetadata;

import testing.basictesting.fixtures.WithdrawMoneyCommand;

// tag::when-command-with-metadata[]
import org.axonframework.messaging.core.Metadata;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .command(new WithdrawMoneyCommand("account-1", 100.00), Metadata.with("userId", "user-123"));
               // then...
    }
}
// end::when-command-with-metadata[]
