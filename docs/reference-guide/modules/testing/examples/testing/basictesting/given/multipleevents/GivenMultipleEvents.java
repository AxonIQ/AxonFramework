package testing.basictesting.given.multipleevents;

import testing.basictesting.fixtures.AccountCreatedEvent;
import testing.basictesting.fixtures.MoneyDepositedEvent;

// tag::given-multiple-events[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .events(
                       new AccountCreatedEvent("account-1", 500.00),
                       new MoneyDepositedEvent("account-1", 100.00)
               );
               // when...
    }
}
// end::given-multiple-events[]
