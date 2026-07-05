package testing.basictesting.given.singleeventwithmetadata;

import testing.basictesting.fixtures.AccountCreatedEvent;

// tag::given-single-event-with-metadata[]
import org.axonframework.messaging.core.Metadata;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .event(new AccountCreatedEvent("account-1", 500.00), Metadata.with("userId", "user-123"));
               // when...
    }
}
// end::given-single-event-with-metadata[]
