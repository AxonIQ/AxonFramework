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
