package testing.basictesting.then.resultmessagesatisfies;

import testing.basictesting.fixtures.CreateAccountCommand;

// tag::then-result-message-satisfies[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new CreateAccountCommand("account-1", 500.00))
               .then()
               .resultMessageSatisfies(
                       result -> result.payloadAs(String.class).equals("account-1")
               );
    }
}
// end::then-result-message-satisfies[]
