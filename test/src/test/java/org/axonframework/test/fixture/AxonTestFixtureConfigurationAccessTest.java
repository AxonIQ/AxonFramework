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

package org.axonframework.test.fixture;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link AxonTestFixture#configuration()}, which hands out the configuration the fixture started so a caller
 * can resolve components without entering a phase.
 *
 * @author Mateusz Nowak
 */
class AxonTestFixtureConfigurationAccessTest {

    @Test
    void configurationResolvesTheComponentsTheFixtureWasBuiltFrom() {
        // given
        var fixture = AxonTestFixture.with(messagingConfigurer());

        // when
        Configuration configuration = fixture.configuration();

        // then
        assertThat(configuration.getComponent(CommandBus.class)).isNotNull();
    }

    @Test
    void configurationIsTheSameInstanceThePhasesHandOut() {
        // given
        var fixture = AxonTestFixture.with(messagingConfigurer());
        AtomicReference<Configuration> fromGiven = new AtomicReference<>();
        AtomicReference<Configuration> fromThen = new AtomicReference<>();

        // when
        fixture.given()
               .execute(fromGiven::set)
               .when()
               .nothing()
               .then()
               .expect(fromThen::set);

        // then
        assertThat(fromGiven.get()).isSameAs(fixture.configuration());
        assertThat(fromThen.get()).isSameAs(fixture.configuration());
    }

    @Test
    void aChainedScenarioKeepsTheSameConfiguration() {
        // given a fixture continued through and(), which reuses the already started configuration
        var fixture = AxonTestFixture.with(messagingConfigurer());
        AtomicReference<Configuration> fromChainedScenario = new AtomicReference<>();

        // when
        fixture.given()
               .noPriorActivity()
               .when()
               .nothing()
               .then()
               .and()
               .given()
               .execute(fromChainedScenario::set);

        // then
        assertThat(fromChainedScenario.get()).isSameAs(fixture.configuration());
    }

    private static MessagingConfigurer messagingConfigurer() {
        return MessagingConfigurer.create()
                                  .componentRegistry(ComponentRegistry::disableEnhancerScanning);
    }
}
