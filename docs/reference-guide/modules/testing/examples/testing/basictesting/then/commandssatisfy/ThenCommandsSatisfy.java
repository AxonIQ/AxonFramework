package testing.basictesting.then.commandssatisfy;

import testing.basictesting.fixtures.AccountClosedEvent;
import testing.basictesting.fixtures.SendEmailCommand;

// tag::then-commands-satisfy[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .event(new AccountClosedEvent("account-1"))
               .then()
               .commandsSatisfy(commands -> {
                   assertEquals(1, commands.size());
                   SendEmailCommand command = commands.getFirst().payloadAs(SendEmailCommand.class);
                   assertEquals("user@example.com", command.recipient());
               });
    }
}
// end::then-commands-satisfy[]
