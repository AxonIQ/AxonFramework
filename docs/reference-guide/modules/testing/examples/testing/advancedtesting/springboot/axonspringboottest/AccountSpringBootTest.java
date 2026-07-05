package testing.advancedtesting.springboot.axonspringboottest;

import testing.advancedtesting.fixtures.AccountCreatedEvent;
import testing.advancedtesting.fixtures.MoneyWithdrawnEvent;
import testing.advancedtesting.fixtures.WithdrawMoneyCommand;

// tag::axon-spring-boot-test[]
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

@AxonSpringBootTest(properties = "axon.axonserver.enabled=false")
class AccountSpringBootTest {

    @Autowired
    private AxonTestFixture fixture;

    @Test
    void testWithSpringConfiguration() {
        fixture.given()
               .event(new AccountCreatedEvent("account-1", 500.00))
               .when()
               .command(new WithdrawMoneyCommand("account-1", 100.00))
               .then()
               .success()
               .events(new MoneyWithdrawnEvent("account-1", 100.00));
    }
}
// end::axon-spring-boot-test[]
