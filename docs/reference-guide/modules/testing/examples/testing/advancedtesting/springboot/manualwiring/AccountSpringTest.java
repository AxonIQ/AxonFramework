/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
