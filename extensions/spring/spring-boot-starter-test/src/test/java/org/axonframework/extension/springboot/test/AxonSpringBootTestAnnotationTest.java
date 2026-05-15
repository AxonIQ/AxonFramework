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

package org.axonframework.extension.springboot.test;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying that {@link AxonSpringBootTest @AxonSpringBootTest} correctly bootstraps the Axon test
 * infrastructure in a Spring Boot context.
 *
 * @author Mateusz Nowak
 */
@AxonSpringBootTest(
        classes = AxonSpringBootTestAnnotationTest.TestApplication.class,
        properties = "axon.axonserver.enabled=false"
)
class AxonSpringBootTestAnnotationTest {

    record Ping() {

    }

    record Pong() {

    }

    static class PingHandler {

        @CommandHandler
        Pong handle(Ping command) {
            return new Pong();
        }
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    static class TestApplication {

        @Bean
        PingHandler pingHandler() {
            return new PingHandler();
        }
    }

    @Autowired
    AxonTestFixture fixture;

    @Test
    void axonTestFixtureIsAvailableAsBean() {
        assertNotNull(fixture);
    }

    @Test
    void fixtureSupportsGivenWhenThenWithCommandResult() {
        fixture.when()
               .command(new Ping())
               .then()
               .success()
               .resultMessagePayload(new Pong());
    }

    /**
     * Verifies that when {@code axon.axonserver.enabled=false} is set and no explicit
     * {@link AxonTestFixture.Customization} bean is present, {@link AxonTestConfiguration} derives the default
     * customization from the property, resulting in Axon Server being disabled in the fixture.
     * <p>
     * Uses reflection to access the private {@code customization} field because
     * {@link AxonTestFixture} does not expose a public accessor for it.
     */
    @Test
    void defaultCustomizationDisablesAxonServerWhenPropertyIsFalse() throws Exception {
        Field customizationField = AxonTestFixture.class.getDeclaredField("customization");
        customizationField.setAccessible(true);
        var customization = (AxonTestFixture.Customization) customizationField.get(fixture);

        assertFalse(customization.integrationEnabled(),
                    "Axon Server should be disabled in the default Customization when axon.axonserver.enabled=false");
    }

}
