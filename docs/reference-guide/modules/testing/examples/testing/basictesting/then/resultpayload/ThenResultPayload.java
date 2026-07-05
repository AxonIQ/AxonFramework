package testing.basictesting.then.resultpayload;

import testing.basictesting.fixtures.CreateAccountCommand;

// tag::then-result-payload[]
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
               .resultMessagePayload("account-1");
    }
}
// end::then-result-payload[]
