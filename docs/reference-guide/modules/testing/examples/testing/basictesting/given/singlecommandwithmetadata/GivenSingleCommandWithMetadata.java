package testing.basictesting.given.singlecommandwithmetadata;

import testing.basictesting.fixtures.CreateAccountCommand;

// tag::given-single-command-with-metadata[]
import org.axonframework.messaging.core.Metadata;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .command(new CreateAccountCommand("account-1", 500.00), Metadata.with("userId", "user-123"));
               // when...
    }
}
// end::given-single-command-with-metadata[]
