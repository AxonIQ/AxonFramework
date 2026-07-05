package testing.basictesting.then.commandsmatch;

import testing.basictesting.fixtures.AccountClosedEvent;
import testing.basictesting.fixtures.SendEmailCommand;

// tag::then-commands-match[]
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
               .commandsMatch(commands -> commands.size() == 1
                       && commands.getFirst().payloadAs(SendEmailCommand.class)
                                  .recipient()
                                  .equals("user@example.com"));
    }
}
// end::then-commands-match[]
