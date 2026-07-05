package testing.matchersandfieldfilters.fieldfilters.plaintest;

import testing.matchersandfieldfilters.fieldfilters.AccountCreatedEvent;
import testing.matchersandfieldfilters.fixtures.CreateAccountCommand;

// tag::plain-test-without-field-filter[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new CreateAccountCommand(500.00))
               .then()
               .events(new AccountCreatedEvent("???", 500.00));
               // Verify fails as we are unaware of the accountId
    }
}
// end::plain-test-without-field-filter[]
