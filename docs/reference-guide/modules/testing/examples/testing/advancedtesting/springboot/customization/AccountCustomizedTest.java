package testing.advancedtesting.springboot.customization;

import testing.advancedtesting.fixtures.AccountCreatedEvent;
import testing.advancedtesting.fixtures.CreateAccountCommand;

// tag::axon-spring-boot-test-customization[]
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@AxonSpringBootTest
class AccountCustomizedTest {

    @TestConfiguration
    static class TestConfig {

        @Bean
        public AxonTestFixture.Customization customization() {
            return new AxonTestFixture.Customization().asIntegrationTest();
        }
    }

    @Autowired
    private AxonTestFixture fixture;

    @Test
    void testWithCustomization() {
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new CreateAccountCommand("account-1", 500.00))
               .then()
               .success()
               .events(new AccountCreatedEvent("account-1", 500.00));
    }
}
// end::axon-spring-boot-test-customization[]
