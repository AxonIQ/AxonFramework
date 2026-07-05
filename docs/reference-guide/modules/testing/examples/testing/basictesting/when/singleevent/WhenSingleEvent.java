package testing.basictesting.when.singleevent;

import testing.basictesting.fixtures.ExternalPaymentReceivedEvent;

// tag::when-single-event[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .events(new ExternalPaymentReceivedEvent("account-1", 100.00));
               // then...
    }
}
// end::when-single-event[]
