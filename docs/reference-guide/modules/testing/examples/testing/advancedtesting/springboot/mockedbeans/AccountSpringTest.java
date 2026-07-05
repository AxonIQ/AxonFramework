package testing.advancedtesting.springboot.mockedbeans;

import testing.advancedtesting.fixtures.AccountCreatedEvent;
import testing.advancedtesting.fixtures.MoneyWithdrawnEvent;
import testing.advancedtesting.fixtures.WithdrawMoneyCommand;

// tag::verifying-mocked-spring-beans-import[]
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.mockito.Mockito.*;

// end::verifying-mocked-spring-beans-import[]

// Minimal service used only to demonstrate verifying a mocked Spring bean; not otherwise elaborated in this
// example.
interface MyService {

    void invoked();
}

// tag::verifying-mocked-spring-beans-class[]
@AxonSpringBootTest(properties = "axon.axonserver.enabled=false")
class AccountSpringTest {

    @TestConfiguration
    static class TestConfig {

        @Bean
        public MyService myService() {
            return mock(MyService.class);
        }
    }

    @Autowired
    private AxonTestFixture fixture;

    @Test
    void testWithSpringConfigurationAndMockedBean() {
        fixture.given()
               .event(new AccountCreatedEvent("account-1", 500.00))
               .when()
               .command(new WithdrawMoneyCommand("account-1", 100.00))
               .then()
               .success()
               .events(new MoneyWithdrawnEvent("account-1", 100.00))
               .expect(config -> {
                   MyService myService = config.getComponent(MyService.class);
                   verify(myService).invoked();
               });
    }
}
// end::verifying-mocked-spring-beans-class[]
