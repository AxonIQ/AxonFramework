package testing.basictesting.then.nocommands;

import testing.basictesting.fixtures.AccountClosedEvent;

// tag::then-no-commands[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .event(new AccountClosedEvent("account-1"))
               .then()
               .noCommands();
    }
}
// end::then-no-commands[]
