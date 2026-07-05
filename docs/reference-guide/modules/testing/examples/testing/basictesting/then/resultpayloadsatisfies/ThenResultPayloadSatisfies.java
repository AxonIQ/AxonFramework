package testing.basictesting.then.resultpayloadsatisfies;

import testing.basictesting.fixtures.CreateAccountCommand;

// tag::then-result-payload-satisfies[]
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
               .resultMessagePayloadSatisfies(
                       String.class,
                       result -> result.equals("account-1")
               );
    }
}
// end::then-result-payload-satisfies[]
