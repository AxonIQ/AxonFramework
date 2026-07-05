package testing.advancedtesting.springboot.manualwiring;

import testing.advancedtesting.fixtures.AccountCreatedEvent;
import testing.advancedtesting.fixtures.MoneyWithdrawnEvent;
import testing.advancedtesting.fixtures.WithdrawMoneyCommand;

// tag::spring-boot-manual-wiring[]
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.test.fixture.AxonTestFixture;
import org.axonframework.test.fixture.MessagesRecordingConfigurationEnhancer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest
class AccountSpringTest {

    @TestConfiguration
    static class TestConfig {

        @Bean
        public MessagesRecordingConfigurationEnhancer recordingEnhancer() {
            return new MessagesRecordingConfigurationEnhancer();
        }
    }

    @Autowired
    private AxonConfiguration configuration;

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new AxonTestFixture(configuration, new AxonTestFixture.Customization());
    }

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

    @AfterEach
    void tearDown() {
        fixture.stop();
    }
}
// end::spring-boot-manual-wiring[]
