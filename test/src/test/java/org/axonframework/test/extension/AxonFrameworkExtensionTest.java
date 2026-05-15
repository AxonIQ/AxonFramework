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

package org.axonframework.test.extension;

import org.axonframework.test.fixture.AxonTestFixture;
import org.axonframework.test.fixture.AxonTestFixture.Customization;
import org.axonframework.test.fixture.sampledomain.CourseCreated;
import org.axonframework.test.fixture.sampledomain.CourseEntity;
import org.axonframework.test.fixture.sampledomain.CreateCourse;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;

import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AxonFrameworkExtensionTest {

    private static final AxonTestFixture mockedFixture = mock(AxonTestFixture.class);

    static void assertCreateNewCourse(AxonTestFixture fixture) {
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new CreateCourse(1, "Foo"))
               .then()
               .events(new CourseCreated(1, "Foo"));
    }

    @Nested
    @ExtendWith(AxonFrameworkExtension.class)
    class DomainTestWithExtensionAndProviderField {

        @ProvidedAxonTestFixture
        AxonTestFixtureProvider fixtureProvider = () -> AxonTestFixture.with(CourseEntity.configurer(),
                                                                             Customization::asIntegrationTest);

        @Test
        void creatingNewCourseIssuesEvent(@NonNull AxonTestFixture fixture) {
            assertCreateNewCourse(fixture);
        }
    }

    @Nested
    @ExtendWith(AxonFrameworkExtension.class)
    class DomainTestWithExtensionButWithoutProviderField {

        @Test
        void shouldRunAlthoughNoProviderIsConfigured() {
            assertThat(1 + 1).isEqualTo(2);
        }
    }

    @Test
    void shouldFailIfFixtureRequestedAsParameter() throws NoSuchMethodException {
        AxonFrameworkExtension extension = new AxonFrameworkExtension();
        ParameterContext parameterContext = mock(ParameterContext.class);
        Parameter parameter = ParameterFailingTest.class.getDeclaredMethod("shouldFail", AxonTestFixture.class)
                                                        .getParameters()[0];
        when(parameterContext.getParameter()).thenReturn(parameter);
        ExtensionContext extensionContext = mock(ExtensionContext.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        when(extensionContext.getStore(any())).thenReturn(store);
        // store.get returns null by default

        assertThatThrownBy(() -> extension.resolveParameter(parameterContext, extensionContext))
                .isInstanceOf(ParameterResolutionException.class)
                .hasMessageContaining("No AxonTestFixture provider found");
    }

    static class ParameterFailingTest {

        void shouldFail(AxonTestFixture fixture) {
        }
    }

    @Nested
    class LifecycleAndParameterResolverTest {

        @Test
        @ExtendWith(AxonFrameworkExtension.class)
        @ProvidedAxonTestFixture(MockedFixtureProvider.class)
        void fixtureIsStoppedAfterTest(AxonTestFixture fixture) {
            assertThat(fixture).isSameAs(mockedFixture);
        }

        @AfterAll
        static void verifyStopped() {
            verify(mockedFixture, atLeastOnce()).stop();
        }
    }

    public static class MockedFixtureProvider implements AxonTestFixtureProvider {

        @Override
        public AxonTestFixture get() {
            return mockedFixture;
        }
    }

    @Nested
    class DomainTestWithoutExtension {

        private AxonTestFixture fixture;

        @Test
        void creatingNewCourseIssuesEvent() {
            assertCreateNewCourse(fixture);
        }

        @BeforeEach
        void setUp() {
            fixture = AxonTestFixture.with(CourseEntity.configurer(), Customization::asIntegrationTest);
        }

        @AfterEach
        void tearDown() {
            fixture.stop();
        }
    }
}
