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

package org.axonframework.examples.university

import org.axonframework.common.configuration.ApplicationConfigurer
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
import org.axonframework.test.extension.AxonTestFixtureProvider
import org.axonframework.test.fixture.AxonTestFixture

data object TestFixtures {

    fun universityFixture(configuration: EventSourcingConfigurer.() -> ApplicationConfigurer): AxonTestFixtureProvider = AxonTestFixtureProvider {
        AxonTestFixture.with(
            UniversityKotlinApplication.configurer().configuration()
        )
    }

}
