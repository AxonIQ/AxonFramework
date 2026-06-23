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

package org.axonframework.common.configuration;

import org.jspecify.annotations.NonNull;
import org.axonframework.common.TypeReference;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.lifecycle.LifecycleHandlerInvocationException;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.axonframework.common.util.AssertUtils.assertWithin;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test suite validating the workings of the lifecycle operations registered and invoked on
 * {@link ApplicationConfigurer} implementations and the resulting {@link AxonConfiguration}.
 * <p>
 * As such, operations like the {@link LifecycleRegistry#onStart(int, LifecycleHandler)},
 * {@link LifecycleRegistry#onShutdown(int, LifecycleHandler)}, {@link ApplicationConfigurer#start()},
 * {@link AxonConfiguration#start()} and {@link AxonConfiguration#shutdown()} will be tested.
 *
 * @author Steven van Beelen
 */
public abstract class ApplicationConfigurerTestSuite<C extends ApplicationConfigurer> {

    private static final String START_FAILURE_EXCEPTION_MESSAGE = "some start failure";
    private static final String INIT_STATE = "initial-state";
    protected static final TestComponent TEST_COMPONENT = new TestComponent(INIT_STATE);
    protected static final SpecificTestComponent SPECIFIC_TEST_COMPONENT = new SpecificTestComponent(INIT_STATE);

    protected C testSubject;
    private AxonConfiguration configuration;

    @BeforeEach
    void setUp() {
        testSubject = createConfigurer();
    }

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    /**
     * Builds the {@link ApplicationConfigurer} of type {@code C} to be used in this test suite for validating its
     * start-up and shutdown behavior.
     *
     * @return The {@link ApplicationConfigurer} of type {@code C} to be used in this test suite for validating its
     * start-up and shutdown behavior.
     */
    public abstract C createConfigurer();

    private AxonConfiguration buildConfiguration() {
        initialize(testSubject);
        configuration = testSubject.build();
        return configuration;
    }

    /**
     * Initializes the given {@code testSubject}.
     * <p>
     * Does nothing by default.
     *
     * @param testSubject The test subject of type {@code C} to initialize.
     */
    protected void initialize(C testSubject) {
        // No-op
    }

    /**
     * Boolean stating whether the {@link ApplicationConfigurer} under test supports {@link Component} overriding.
     *
     * @return {@code true} when {@link Component Components} can be overridden, {@code false} otherwise.
     */
    public boolean supportsOverriding() {
        return true;
    }

    /**
     * Boolean stating whether the {@link ApplicationConfigurer} under test supports the use of
     * {@link ComponentFactory ComponentFactories}.
     *
     * @return {@code true} when {@link ComponentFactory ComponentFactories} are supported, {@code false} otherwise.
     */
    public boolean supportsComponentFactories() {
        return true;
    }

    /**
     * Boolean stating whether the {@link ApplicationConfigurer} under test does its own lifecycle management through
     * the {@link LifecycleRegistry}.
     *
     * @return {@code true} when the {@link ApplicationConfigurer} under test controls the lifecycle management through
     * the {@link LifecycleRegistry}, {@code false} otherwise.
     */
    public boolean doesOwnLifecycleManagement() {
        return true;
    }

    protected static class TestComponent {

        private final String state;

        protected TestComponent(String state) {
            this.state = state;
        }

        protected String state() {
            return state;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TestComponent that = (TestComponent) o;
            return Objects.equals(state, that.state);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(state);
        }
    }

    protected static class SpecificTestComponent extends TestComponent {

        protected SpecificTestComponent(String state) {
            super(state);
        }
    }

    protected static class GenericTestComponent<T> {

        private final T state;

        protected GenericTestComponent(T state) {
            this.state = state;
        }

        public T getState() {
            return state;
        }
    }

    protected static class StringTestComponent extends GenericTestComponent<String> {

        protected StringTestComponent(String state) {
            super(state);
        }
    }

    protected static class TestModule extends BaseModule<TestModule> {

        protected TestModule(String name) {
            super(name);
        }
    }

    protected static class TestComponentFactory implements ComponentFactory<TestComponent> {

        private final String state;
        private final AtomicBoolean construct;
        private final AtomicBoolean shutdownInvoked;

        private TestComponentFactory(String state) {
            this(state, new AtomicBoolean(true));
        }

        private TestComponentFactory(String state, AtomicBoolean construct) {
            this(state, construct, new AtomicBoolean(false));
        }

        private TestComponentFactory(String state, AtomicBoolean construct, AtomicBoolean shutdownInvoked) {
            this.state = state;
            this.construct = construct;
            this.shutdownInvoked = shutdownInvoked;
        }

        @NonNull
        @Override
        public Class<TestComponent> forType() {
            return TestComponent.class;
        }

        @NonNull
        @Override
        public Optional<Component<TestComponent>> construct(@NonNull String name,
                                                            @NonNull Configuration config) {
            if (construct.get()) {
                return Optional.of(new InstantiatedComponentDefinition<>(
                        new Component.Identifier<>(forType(), name),
                        new TestComponent(state)
                ));
            }

            return Optional.empty();
        }

        @Override
        public void registerShutdownHandlers(@NonNull LifecycleRegistry registry) {
            registry.onShutdown(() -> shutdownInvoked.set(true));
        }

        @Override
        public void describeTo(@NonNull ComponentDescriptor descriptor) {
            // Nothing to do here
        }
    }

    @Nested
    class ComponentRegistration {

        @Test
        void registerComponentForTypeExposesRegisteredComponentOnGet() {
            TestComponent testComponent = TEST_COMPONENT;
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, c -> testComponent));

            Configuration config = buildConfiguration();

            assertEquals(testComponent, config.getComponent(TestComponent.class));
        }

        @Test
        void registerComponentForTypeExposesRegisteredComponentOnGetWhenAssignableFrom() {
            SpecificTestComponent testComponent = SPECIFIC_TEST_COMPONENT;
            testSubject.componentRegistry(
                    cr -> cr.registerComponent(SpecificTestComponent.class, c -> testComponent)
            );

            Configuration config = buildConfiguration();

            assertEquals(testComponent, config.getComponent(TestComponent.class));
        }

        @Test
        void registerComponentForTypeAndNameExposesRegisteredComponentOnGet() {
            TestComponent testComponent = TEST_COMPONENT;
            String testName = "some-name";
            testSubject.componentRegistry(
                    cr -> cr.registerComponent(TestComponent.class, testName, c -> testComponent)
            );

            Configuration config = buildConfiguration();

            assertEquals(testComponent, config.getComponent(TestComponent.class, testName));
        }

        @Test
        void registerComponentForTypeAndNameExposesRegisteredComponentWhenAssignableFrom() {
            SpecificTestComponent testComponent = SPECIFIC_TEST_COMPONENT;
            String testName = "some-name";
            testSubject.componentRegistry(
                    cr -> cr.registerComponent(SpecificTestComponent.class, testName, c -> testComponent)
            );

            Configuration config = buildConfiguration();

            assertEquals(testComponent, config.getComponent(TestComponent.class, testName));
        }

        @Test
        void registerComponentForTypeExposesRegisteredComponentOnOptionalGet() {
            TestComponent testComponent = TEST_COMPONENT;
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, c -> testComponent));

            Configuration config = buildConfiguration();

            Optional<TestComponent> result = config.getOptionalComponent(TestComponent.class);

            assertTrue(result.isPresent());
            assertEquals(testComponent, result.get());
        }

        @Test
        void registerComponentForTypeExposesRegisteredComponentOnOptionalGetWhenAssignableFrom() {
            SpecificTestComponent testComponent = SPECIFIC_TEST_COMPONENT;
            testSubject.componentRegistry(
                    cr -> cr.registerComponent(SpecificTestComponent.class, c -> testComponent)
            );

            Configuration config = buildConfiguration();

            Optional<TestComponent> result = config.getOptionalComponent(TestComponent.class);

            assertTrue(result.isPresent());
            assertEquals(testComponent, result.get());
        }

        @Test
        void registerComponentForTypeAndNameExposesRegisteredComponentOnOptionalGet() {
            TestComponent testComponent = TEST_COMPONENT;
            String testName = "some-name";
            testSubject.componentRegistry(
                    cr -> cr.registerComponent(TestComponent.class, testName, c -> testComponent)
            );

            Configuration config = buildConfiguration();

            Optional<TestComponent> result = config.getOptionalComponent(TestComponent.class, testName);

            assertTrue(result.isPresent());
            assertEquals(testComponent, result.get());
        }

        @Test
        void registerComponentForTypeAndNameExposesRegisteredComponentOnOptionalGetWhenAssignableFrom() {
            SpecificTestComponent testComponent = SPECIFIC_TEST_COMPONENT;
            String testName = "some-name";
            testSubject.componentRegistry(
                    cr -> cr.registerComponent(SpecificTestComponent.class, testName, c -> testComponent)
            );

            Configuration config = buildConfiguration();

            Optional<TestComponent> result = config.getOptionalComponent(TestComponent.class, testName);

            assertTrue(result.isPresent());
            assertEquals(testComponent, result.get());
        }

        @Test
        void getOptionalComponentResultsInEmptyOptionalForUnregisteredComponent() {
            Optional<TestComponent> result = buildConfiguration().getOptionalComponent(TestComponent.class);

            assertFalse(result.isPresent());
        }

        @Test
        void canRegisterMultipleComponentsOfTheSameTypeForDifferentNames() {
            String testNameOne = "one";
            String testNameTwo = "two";
            TestComponent testComponentOne = new TestComponent(testNameOne);
            TestComponent testComponentTwo = new TestComponent(testNameTwo);
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class,
                                                                     testNameOne,
                                                                     c -> testComponentOne).registerComponent(
                    TestComponent.class,
                    testNameTwo,
                    c -> testComponentTwo));

            Configuration config = buildConfiguration();

            assertEquals(testComponentOne, config.getComponent(TestComponent.class, testNameOne));
            assertEquals(testComponentTwo, config.getComponent(TestComponent.class, testNameTwo));
        }

        @Test
        void componentBuilderIsInvokedOnceUponRetrievalOfComponent() {
            AtomicInteger invocationCounter = new AtomicInteger(0);
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, "name", c -> {
                invocationCounter.incrementAndGet();
                return TEST_COMPONENT;
            }));

            Configuration config = buildConfiguration();

            assertEquals(0, invocationCounter.get());
            config.getComponent(TestComponent.class, "name");
            assertEquals(1, invocationCounter.get());
            config.getComponent(TestComponent.class, "name");
            assertEquals(1, invocationCounter.get());
        }

        @Test
        void registeringComponentsForTheSameTypeReplacesThePreviousComponentBuilder() {
            Assumptions.assumeTrue(supportsOverriding(), "Ignore test since Component overriding is not supported.");

            TestComponent testComponent = new TestComponent("replaced-component");
            TestComponent expectedComponent = new TestComponent("the-winner");
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, c -> testComponent)
                                                  .registerComponent(TestComponent.class, c -> expectedComponent));

            Configuration config = buildConfiguration();

            assertNotEquals(testComponent, config.getComponent(TestComponent.class));
            assertEquals(expectedComponent, config.getComponent(TestComponent.class));
        }

        @Test
        void registeringComponentsForTheSameTypeAndNameReplacesThePreviousComponentBuilder() {
            Assumptions.assumeTrue(supportsOverriding(), "Ignore test since Component overriding is not supported.");

            TestComponent testComponent = new TestComponent("replaced-component");
            TestComponent expectedComponent = new TestComponent("the-winner");
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, "name", c -> testComponent)
                                                  .registerComponent(TestComponent.class,
                                                                     "name",
                                                                     c -> expectedComponent));

            Configuration config = buildConfiguration();

            assertNotEquals(testComponent, config.getComponent(TestComponent.class, "name"));
            assertEquals(expectedComponent, config.getComponent(TestComponent.class, "name"));
        }

        @Test
        void getComponentWithDefaultInvokesSupplierWhenThereIsNoRegisteredComponentForTheGivenClass() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            TestComponent defaultComponent = new TestComponent("default");
            TestComponent registeredComponent = TEST_COMPONENT;
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class,
                                                                     "id",
                                                                     c -> registeredComponent));

            Configuration config = buildConfiguration();

            TestComponent result = config.getComponent(TestComponent.class, "id", () -> {
                invoked.set(true);
                return defaultComponent;
            });

            assertFalse(invoked.get());
            assertNotEquals(defaultComponent, result);
            assertEquals(registeredComponent, result);

            invoked.set(false);
            result = config.getComponent(TestComponent.class, "non-registered-component", () -> {
                invoked.set(true);
                return defaultComponent;
            });

            assertTrue(invoked.get());
            assertEquals(defaultComponent, result);
            assertNotEquals(registeredComponent, result);
        }

        @Test
        void getComponentsReturnsEmptyMapWhenNoComponentsOfTypeExist() {
            // given
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, c -> TEST_COMPONENT));

            // when
            Configuration config = buildConfiguration();
            Map<String, SpecificTestComponent> result = config.getComponents(SpecificTestComponent.class);

            // then
            assertTrue(result.isEmpty());
        }

        @Test
        void getComponentsReturnsUnnamedComponentWithNullKey() {
            // given
            TestComponent component = TEST_COMPONENT;
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, c -> component));

            // when
            Configuration config = buildConfiguration();
            Map<String, TestComponent> result = config.getComponents(TestComponent.class);

            // then
            assertEquals(1, result.size());
            assertTrue(containsNotNamedComponent(result));
            assertSame(component, result.get(null));
        }

        @Test
        void getComponentsReturnsNamedComponentsWithTheirNames() {
            // given
            TestComponent componentOne = new TestComponent("one");
            TestComponent componentTwo = new TestComponent("two");
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, "name-one", c -> componentOne)
                                                  .registerComponent(TestComponent.class,
                                                                     "name-two",
                                                                     c -> componentTwo));

            // when
            Configuration config = buildConfiguration();
            Map<String, TestComponent> result = config.getComponents(TestComponent.class);

            // then
            assertEquals(2, result.size());
            assertTrue(result.containsKey("name-one"));
            assertTrue(result.containsKey("name-two"));
            assertSame(componentOne, result.get("name-one"));
            assertSame(componentTwo, result.get("name-two"));
        }

        @Test
        void getComponentsReturnsMixOfNamedAndUnnamedComponents() {
            // given
            TestComponent unnamedComponent = new TestComponent("unnamed");
            TestComponent namedComponent = new TestComponent("named");
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, c -> unnamedComponent)
                                                  .registerComponent(TestComponent.class,
                                                                     "named",
                                                                     c -> namedComponent));

            // when
            Configuration config = buildConfiguration();
            Map<String, TestComponent> result = config.getComponents(TestComponent.class);

            // then
            assertEquals(2, result.size());
            assertTrue(containsNotNamedComponent(result));
            assertTrue(result.containsKey("named"));
            assertSame(unnamedComponent, result.get(null));
            assertSame(namedComponent, result.get("named"));
        }

        @Test
        void getComponentsReturnsComponentsMatchingSubtypes() {
            // given
            SpecificTestComponent specificComponent = SPECIFIC_TEST_COMPONENT;
            testSubject.componentRegistry(cr -> cr.registerComponent(SpecificTestComponent.class,
                                                                     c -> specificComponent));

            // when
            Configuration config = buildConfiguration();
            Map<String, TestComponent> result = config.getComponents(TestComponent.class);
            // then
            assertEquals(1, result.size());
            assertTrue(result.containsKey(null));
            assertSame(specificComponent, result.get(null));
        }

        @Test
        void getComponentsReturnsImmutableMap() {
            // given
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, c -> TEST_COMPONENT));

            // when
            Configuration config = buildConfiguration();
            Map<String, TestComponent> result = config.getComponents(TestComponent.class);

            // then
            assertThrows(UnsupportedOperationException.class, () -> result.put("new", TEST_COMPONENT));
        }

        @Test
        void getUnnamedComponentsByTypeRefReturnsMatchingGenericType() {
            // given
            StringTestComponent exInstance = new StringTestComponent("state-abc");
            ComponentDefinition<GenericTestComponent<String>> exComponentDef =
                    ComponentDefinition.ofType(new TypeReference<GenericTestComponent<String>>() {
                                       })
                                       .withInstance(exInstance);

            testSubject.componentRegistry(cr -> cr.registerComponent(exComponentDef));

            // when / then
            Configuration config = buildConfiguration();

            assertEquals(exInstance, config.<GenericTestComponent<String>>getComponent(new TypeReference<>() {
            }));
            assertTrue(config.<GenericTestComponent<String>>getOptionalComponent(new TypeReference<>() {
            }).isPresent());
            assertFalse(config.<GenericTestComponent<Double>>getOptionalComponent(new TypeReference<>() {
            }).isPresent());
        }

        @Test
        void getNamedComponentsByTypeRefReturnsMatchingGenericType() {
            // given
            StringTestComponent exInstance = new StringTestComponent("state-abc");
            GenericTestComponent<Integer> exOtherInstance = new GenericTestComponent<>(1234);
            String exComponentName = "stringComponent";
            ComponentDefinition<GenericTestComponent<String>> exComponentDef =
                    ComponentDefinition.ofTypeAndName(new TypeReference<GenericTestComponent<String>>() {
                                       }, exComponentName)
                                       .withInstance(exInstance);
            ComponentDefinition<GenericTestComponent<Integer>> exOtherComponentDef =
                    ComponentDefinition.ofTypeAndName(new TypeReference<GenericTestComponent<Integer>>() {
                                       }, "integerComponent")
                                       .withInstance(exOtherInstance);

            testSubject.componentRegistry(cr -> cr.registerComponent(exComponentDef));
            testSubject.componentRegistry(cr -> cr.registerComponent(exOtherComponentDef));

            // when / then
            Configuration config = buildConfiguration();

            assertEquals(exInstance, config.<GenericTestComponent<String>>getComponent(new TypeReference<>() {
            }, exComponentName));
            assertThrows(ComponentNotFoundException.class,
                         () -> config.<GenericTestComponent<String>>getComponent(new TypeReference<>() {
                         }, "otherStringComponent"));
            assertTrue(config.<GenericTestComponent<String>>getOptionalComponent(new TypeReference<>() {
            }, exComponentName).isPresent());
            assertFalse(config.<GenericTestComponent<String>>getOptionalComponent(new TypeReference<>() {
            }, "otherStringComponent").isPresent());
        }

        @Test
        @Disabled("TODO #4224 - not supported yet by DefaultComponentRegistry")
        void getUnnamedComponentsByTypeRefReturnsMatchingSubtypeWithGenerics() {
            // given
            StringTestComponent exInstance = new StringTestComponent("state-abc");
            GenericTestComponent<Integer> exOtherInstance = new GenericTestComponent<>(1234);
            ComponentDefinition<StringTestComponent> exComponentDef =
                    ComponentDefinition.ofType(new TypeReference<StringTestComponent>() {
                                       })
                                       .withInstance(exInstance);
            ComponentDefinition<GenericTestComponent<Integer>> exOtherComponentDef =
                    ComponentDefinition.ofType(new TypeReference<GenericTestComponent<Integer>>() {
                                       })
                                       .withInstance(exOtherInstance);

            testSubject.componentRegistry(cr -> cr.registerComponent(exOtherComponentDef));
            testSubject.componentRegistry(cr -> cr.registerComponent(exComponentDef));

            // when / then
            Configuration config = buildConfiguration();

            assertEquals(exInstance,
                         config.<GenericTestComponent<String>>getComponent(new TypeReference<>() {
                         }));
            assertThrows(ComponentNotFoundException.class,
                         () -> config.<GenericTestComponent<Double>>getComponent(new TypeReference<>() {
                         }));
            assertTrue(config.<GenericTestComponent<String>>getOptionalComponent(new TypeReference<>() {
            }).isPresent());
        }

        @Test
        @Disabled("TODO #4224 - not supported yet by DefaultComponentRegistry")
        void getNamedComponentsByTypeRefReturnsMatchingSubtypeWithGenerics() {
            // given
            StringTestComponent exInstance = new StringTestComponent("state-abc");
            GenericTestComponent<Integer> exOtherInstance = new GenericTestComponent<>(1234);
            String exComponentName = "stringComponent";
            ComponentDefinition<StringTestComponent> exComponentDef =
                    ComponentDefinition.ofTypeAndName(new TypeReference<StringTestComponent>() {
                                       }, exComponentName)
                                       .withInstance(exInstance);
            ComponentDefinition<GenericTestComponent<Integer>> exOtherComponentDef =
                    ComponentDefinition.ofTypeAndName(new TypeReference<GenericTestComponent<Integer>>() {
                                       }, "integerComponent")
                                       .withInstance(exOtherInstance);

            testSubject.componentRegistry(cr -> cr.registerComponent(exComponentDef));
            testSubject.componentRegistry(cr -> cr.registerComponent(exOtherComponentDef));

            // when / then
            Configuration config = buildConfiguration();

            assertEquals(exInstance,
                         config.<GenericTestComponent<String>>getComponent(new TypeReference<>() {
                         }));
            assertEquals(exInstance,
                         config.<GenericTestComponent<String>>getComponent(new TypeReference<>() {
                         }, exComponentName));
            assertThrows(ComponentNotFoundException.class,
                         () -> config.<GenericTestComponent<String>>getComponent(new TypeReference<>() {
                         }, "otherStringComponent"));
            assertTrue(config.<GenericTestComponent<String>>getOptionalComponent(new TypeReference<>() {
            }).isPresent());
            assertTrue(config.<GenericTestComponent<String>>getOptionalComponent(new TypeReference<>() {
            }, exComponentName).isPresent());
            assertFalse(config.<GenericTestComponent<String>>getOptionalComponent(new TypeReference<>() {
            }, "otherStringComponent").isPresent());
        }

        @Test
        void getComponentsByTypeRefDoesNotReturnDifferentGenericType() {
            // given
            StringTestComponent exInstance = new StringTestComponent("state-abc");
            ComponentDefinition<GenericTestComponent<String>> exComponentDef =
                    ComponentDefinition.ofType(new TypeReference<GenericTestComponent<String>>() {
                                       })
                                       .withInstance(exInstance);
            testSubject.componentRegistry(cr -> cr.registerComponent(exComponentDef));

            // when / then
            Configuration config = buildConfiguration();
            assertThrows(
                    ComponentNotFoundException.class,
                    () -> config.getComponent(new TypeReference<GenericTestComponent<Double>>() {
                    }));
        }

        @Test
        void getComponentsByTypeRefDoesNotReturnDifferentGenericSubtype() {
            // given
            StringTestComponent exInstance = new StringTestComponent("state-abc");
            ComponentDefinition<StringTestComponent> exComponentDef =
                    ComponentDefinition.ofType(new TypeReference<StringTestComponent>() {
                                       })
                                       .withInstance(exInstance);
            testSubject.componentRegistry(cr -> cr.registerComponent(exComponentDef));

            // when / then
            Configuration config = buildConfiguration();
            assertThrows(
                    ComponentNotFoundException.class,
                    () -> config.getComponent(new TypeReference<GenericTestComponent<Double>>() {
                    }));
        }
    }

    /**
     * When Axon registers a component without a name, Spring uses the FQCN as bean name.
     */
    private static boolean containsNotNamedComponent(Map<String, TestComponent> result) {
        return result.containsKey(null) || result.containsKey(TestComponent.class.getName());
    }

    @Nested
    class ComponentRegistrationFailures {

        @Test
        void registerComponentThrowsNullPointerExceptionForNullType() {
            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class,
                         () -> testSubject.componentRegistry(cr -> cr.registerComponent(null, c -> new Object())));
        }

        @Test
        void registerComponentThrowsIllegalArgumentExceptionForEmptyName() {
            assertThrows(IllegalArgumentException.class,
                         () -> testSubject.componentRegistry(cr -> cr.registerComponent(Object.class,
                                                                                        "",
                                                                                        c -> new Object())));
        }

        @Test
        void registerComponentThrowsNullPointerExceptionForComponentBuilder() {
            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class,
                         () -> testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, null)));
        }

        @Test
        void duplicateRegistrationIsRejectedWhenOverrideModeIsReject() {
            testSubject.componentRegistry(cr -> cr.registerComponent(String.class, c -> "One")
                                                  .setOverridePolicy(OverridePolicy.REJECT));

            assertThrows(ComponentOverrideException.class,
                         () -> testSubject.componentRegistry(cr -> cr.registerComponent(String.class, c -> "Two")));
        }
    }

    @Nested
    class HasComponent {

        @Test
        void hasComponentForClass() {
            testSubject.componentRegistry(cr -> assertFalse(cr.hasComponent(TestComponent.class)));

            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, c -> TEST_COMPONENT));

            testSubject.componentRegistry(cr -> assertTrue(cr.hasComponent(TestComponent.class)));
        }

        @Test
        void hasComponentForClassAndSearchScope() {
            testSubject.componentRegistry(cr -> {
                assertFalse(cr.hasComponent(TestComponent.class, SearchScope.CURRENT));
                assertFalse(cr.hasComponent(TestComponent.class, SearchScope.ALL));
                assertFalse(cr.hasComponent(TestComponent.class, SearchScope.ANCESTORS));
            });

            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, c -> TEST_COMPONENT));

            testSubject.componentRegistry(cr -> {
                assertTrue(cr.hasComponent(TestComponent.class, SearchScope.CURRENT));
                assertTrue(cr.hasComponent(TestComponent.class, SearchScope.ALL));
                assertFalse(cr.hasComponent(TestComponent.class, SearchScope.ANCESTORS));
            });
        }

        @Test
        void hasComponentForClassAndSearchScopeInAncestorsOnly() {
            testSubject.componentRegistry(parentCr -> {
                assertFalse(parentCr.hasComponent(TestComponent.class, SearchScope.CURRENT));
                assertFalse(parentCr.hasComponent(TestComponent.class, SearchScope.ALL));
                assertFalse(parentCr.hasComponent(TestComponent.class, SearchScope.ANCESTORS));
            });

            testSubject.componentRegistry(
                    parentCr -> parentCr.registerComponent(TestComponent.class, c -> TEST_COMPONENT)
            );

            testSubject.componentRegistry(parentCr -> parentCr.registerModule(
                    new TestModule("test-name").componentRegistry(
                            childCr -> childCr.registerEnhancer(registry -> {
                                assertFalse(registry.hasComponent(TestComponent.class, SearchScope.CURRENT));
                                assertTrue(registry.hasComponent(TestComponent.class, SearchScope.ALL));
                                assertTrue(registry.hasComponent(TestComponent.class, SearchScope.ANCESTORS));
                            })
                    )
            ));

            // Building the configuration triggers the ConfigurationEnhancer performing the has component checks.
            buildConfiguration();
        }

        @Test
        void hasComponentForClassAndName() {
            testSubject.componentRegistry(cr -> assertFalse(cr.hasComponent(TestComponent.class, "some-name")));

            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class,
                                                                     "some-name",
                                                                     c -> TEST_COMPONENT));

            testSubject.componentRegistry(cr -> assertTrue(cr.hasComponent(TestComponent.class, "some-name")));
        }

        @Test
        void hasComponentForClassNameAndSearchScope() {
            testSubject.componentRegistry(parentCr -> {
                assertFalse(parentCr.hasComponent(TestComponent.class, "some-name", SearchScope.CURRENT));
                assertFalse(parentCr.hasComponent(TestComponent.class, "some-name", SearchScope.ALL));
                assertFalse(parentCr.hasComponent(TestComponent.class, "some-name", SearchScope.ANCESTORS));
            });

            testSubject.componentRegistry(
                    parentCr -> parentCr.registerComponent(TestComponent.class, "some-name", c -> TEST_COMPONENT)
            );

            testSubject.componentRegistry(parentCr -> parentCr.registerModule(
                    new TestModule("test-name").componentRegistry(
                            childCr -> childCr.registerEnhancer(registry -> {
                                assertFalse(registry.hasComponent(TestComponent.class,
                                                                  "some-name",
                                                                  SearchScope.CURRENT));
                                assertTrue(registry.hasComponent(TestComponent.class, "some-name", SearchScope.ALL));
                                assertTrue(registry.hasComponent(TestComponent.class,
                                                                 "some-name",
                                                                 SearchScope.ANCESTORS
                                ));
                            })
                    )
            ));

            // Building the configuration triggers the ConfigurationEnhancer performing the has component checks.
            buildConfiguration();
        }

        @Test
        void hasComponentForClassNameAndSearchScopeInAncestorsOnly() {
            testSubject.componentRegistry(parentCr -> {
                assertFalse(parentCr.hasComponent(TestComponent.class, "some-name", SearchScope.CURRENT));
                assertFalse(parentCr.hasComponent(TestComponent.class, "some-name", SearchScope.ALL));
                assertFalse(parentCr.hasComponent(TestComponent.class, "some-name", SearchScope.ANCESTORS));
            });

            testSubject.componentRegistry(
                    parentCr -> parentCr.registerComponent(TestComponent.class, "some-name", c -> TEST_COMPONENT)
            );

            testSubject.componentRegistry(parentCr -> parentCr.registerModule(
                    new TestModule("test-name").componentRegistry(
                            childCr -> childCr.registerEnhancer(registry -> {
                                assertFalse(registry.hasComponent(TestComponent.class,
                                                                  "some-name",
                                                                  SearchScope.CURRENT));
                                assertTrue(registry.hasComponent(TestComponent.class,
                                                                 "some-name",
                                                                 SearchScope.ALL));
                                assertTrue(registry.hasComponent(TestComponent.class,
                                                                 "some-name",
                                                                 SearchScope.ANCESTORS));
                            })
                    )
            ));

            // Building the configuration triggers the ConfigurationEnhancer performing the has component checks.
            buildConfiguration();
        }
    }

    @Nested
    class ComponentRegistrationIfPresent {

        @Test
        void registersComponentValidatesForType() {
            // given...
            AtomicBoolean firstConstruction = new AtomicBoolean(false);
            AtomicBoolean secondConstruction = new AtomicBoolean(false);

            testSubject.componentRegistry(cr -> assertFalse(cr.hasComponent(TestComponent.class)));

            // when first registration if present...
            testSubject.componentRegistry(cr -> cr.registerIfNotPresent(TestComponent.class, c -> {
                firstConstruction.set(true);
                return TEST_COMPONENT;
            }));

            // then...
            testSubject.componentRegistry(cr -> assertTrue(cr.hasComponent(TestComponent.class)));
            // Retrieve the component, otherwise the builder is never invoked.
            buildConfiguration().getComponent(TestComponent.class);
            assertTrue(firstConstruction.get());

            // when second registration if present...
            testSubject.componentRegistry(cr -> cr.registerIfNotPresent(TestComponent.class, c -> {
                secondConstruction.set(true);
                return TEST_COMPONENT;
            }));

            // then...
            // Retrieve the component, otherwise the builder is never invoked.
            buildConfiguration().getComponent(TestComponent.class);
            assertFalse(secondConstruction.get());
        }

        @Test
        void registersComponentValidatesForTypeAndName() {
            // given...
            AtomicBoolean firstConstruction = new AtomicBoolean(false);
            AtomicBoolean secondConstruction = new AtomicBoolean(false);
            String testName = "some-name";

            testSubject.componentRegistry(cr -> assertFalse(cr.hasComponent(TestComponent.class, testName)));

            // when first registration if present...
            testSubject.componentRegistry(cr -> cr.registerIfNotPresent(TestComponent.class, testName, c -> {
                firstConstruction.set(true);
                return TEST_COMPONENT;
            }));

            // then...
            testSubject.componentRegistry(cr -> assertTrue(cr.hasComponent(TestComponent.class, testName)));
            // Retrieve the component, otherwise the builder is never invoked.
            buildConfiguration().getComponent(TestComponent.class, testName);
            assertTrue(firstConstruction.get());

            // when second registration if present...
            testSubject.componentRegistry(cr -> cr.registerIfNotPresent(TestComponent.class, testName, c -> {
                secondConstruction.set(true);
                return TEST_COMPONENT;
            }));

            // then...
            // Retrieve the component, otherwise the builder is never invoked.
            buildConfiguration().getComponent(TestComponent.class, testName);
            assertFalse(secondConstruction.get());
        }

        @Test
        void registersComponentValidatesForComponentDefinition() {
            // given...
            AtomicBoolean firstConstruction = new AtomicBoolean(false);
            AtomicBoolean secondConstruction = new AtomicBoolean(false);
            String testName = "some-name";

            testSubject.componentRegistry(cr -> assertFalse(cr.hasComponent(TestComponent.class, testName)));

            // when first registration if present...
            testSubject.componentRegistry(cr -> cr.registerIfNotPresent(
                    ComponentDefinition.ofTypeAndName(TestComponent.class, testName)
                                       .withBuilder(c -> {
                                           firstConstruction.set(true);
                                           return TEST_COMPONENT;
                                       })
            ));

            // then...
            testSubject.componentRegistry(cr -> assertTrue(cr.hasComponent(TestComponent.class, testName)));
            // Retrieve the component, otherwise the builder is never invoked.
            buildConfiguration().getComponent(TestComponent.class, testName);
            assertTrue(firstConstruction.get());

            // when second registration if present...
            testSubject.componentRegistry(cr -> cr.registerIfNotPresent(
                    ComponentDefinition.ofTypeAndName(TestComponent.class, testName)
                                       .withBuilder(c -> {
                                           secondConstruction.set(true);
                                           return TEST_COMPONENT;
                                       })
            ));

            // then...
            // Retrieve the component, otherwise the builder is never invoked.
            buildConfiguration().getComponent(TestComponent.class, testName);
            assertFalse(secondConstruction.get());
        }

        @Test
        void getComponentsReturnsConditionallyRegisteredComponents() {
            // given
            TestComponent firstComponent = new TestComponent("first");
            TestComponent secondComponent = new TestComponent("second");

            testSubject.componentRegistry(cr -> cr.registerIfNotPresent(TestComponent.class,
                                                                        "first",
                                                                        c -> firstComponent)
                                                  .registerIfNotPresent(TestComponent.class,
                                                                        "first",
                                                                        c -> secondComponent));

            // when
            Configuration config = buildConfiguration();
            Map<String, TestComponent> result = config.getComponents(TestComponent.class);

            // then
            assertEquals(1, result.size());
            assertTrue(result.containsKey("first"));
            assertSame(firstComponent, result.get("first"), "Only first component should be present");
        }
    }

    @Nested
    class ComponentDecoration {

        @Test
        void registerDecoratorDecoratesOutcomeOfComponentBuilderInSpecifiedOrder() {
            String expectedState = TEST_COMPONENT.state() + "123";

            testSubject.componentRegistry(
                    cr -> cr.registerComponent(TestComponent.class, config -> TEST_COMPONENT)
                            .registerDecorator(TestComponent.class, 2,
                                               (c, name, delegate) -> new TestComponent(delegate.state + "3"))
                            .registerDecorator(TestComponent.class, 1,
                                               (c, name, delegate) -> new TestComponent(delegate.state + "2"))
                            .registerDecorator(TestComponent.class, "non-existent", 1,
                                               (c, name, delegate) -> new TestComponent(delegate.state + "999"))
                            .registerDecorator(TestComponent.class, 0,
                                               (c, name, delegate) -> new TestComponent(delegate.state + "1"))
            );

            TestComponent result = buildConfiguration().getComponent(TestComponent.class);

            assertEquals(expectedState, result.state());
        }

        @Test
        void registerDecoratorForTypeActsOnImplementationsOfComponents() {
            String expectedState = TEST_COMPONENT.state() + "1";

            testSubject.componentRegistry(
                    cr -> cr.registerComponent(SpecificTestComponent.class, config -> SPECIFIC_TEST_COMPONENT)
                            .registerDecorator(TestComponent.class, 0,
                                               (c, name, delegate) -> new SpecificTestComponent(delegate.state + "1"))
            );

            TestComponent result = buildConfiguration().getComponent(TestComponent.class);

            assertEquals(expectedState, result.state());
        }

        @Test
        void registerDecoratorForTypeAndNameActsOnImplementationsOfComponents() {
            String testName = "some-name";
            String expectedState = TEST_COMPONENT.state() + "1";

            testSubject.componentRegistry(
                    cr -> cr.registerComponent(SpecificTestComponent.class, testName, config -> SPECIFIC_TEST_COMPONENT)
                            .registerDecorator(TestComponent.class, testName, 0,
                                               (c, name, delegate) -> new SpecificTestComponent(delegate.state + "1"))
            );

            TestComponent result = buildConfiguration().getComponent(TestComponent.class, testName);

            assertEquals(expectedState, result.state());
        }

        /**
         * This test registers a SpecificTestComponent, making the Components identifier expect the type
         * SpecificTestComponent. Decorators can act on subtype, but should ensure that the decoration result is
         * assignable to the type used to register the original component. As when that's not done, a ClassCastException
         * is thrown to the user in their code.
         */
        @Test
        void registerDecoratorForTypeThrowsExceptionWithSpecificTraceWhenDecoratedTypeIsNotAssignableToRegisteredType() {
            String testName = "some-name";

            testSubject.componentRegistry(
                    cr -> cr.registerComponent(SpecificTestComponent.class, testName, config -> SPECIFIC_TEST_COMPONENT)
                            .registerDecorator(TestComponent.class, testName, 0,
                                               (c, name, delegate) -> new TestComponent(delegate.state + "1"))
            );

            Configuration config = buildConfiguration();

            assertThatThrownBy(() -> config.getComponent(TestComponent.class, testName)).hasStackTraceContaining(
                    "Make sure decorators return matching components, as component retrieval otherwise fails!"
            );
        }

        @Test
        void getComponentsReturnsDecoratedInstances() {
            // given
            String expectedState = TEST_COMPONENT.state() + "123";
            testSubject.componentRegistry(
                    cr -> cr.registerComponent(TestComponent.class, c -> TEST_COMPONENT)
                            .registerDecorator(TestComponent.class, 0,
                                               (c, name, delegate) -> new TestComponent(delegate.state + "1"))
                            .registerDecorator(TestComponent.class, 1,
                                               (c, name, delegate) -> new TestComponent(delegate.state + "2"))
                            .registerDecorator(TestComponent.class, 2,
                                               (c, name, delegate) -> new TestComponent(delegate.state + "3"))
            );

            // when
            Configuration config = buildConfiguration();
            Map<String, TestComponent> result = config.getComponents(TestComponent.class);

            // then
            assertEquals(1, result.size());
            TestComponent component = result.get(null);
            assertNotNull(component);
            assertEquals(expectedState, component.state(), "Should return decorated instance, not original");
        }
    }

    @Nested
    class ComponentDecorationFailures {

        @Test
        void registerDecoratorThrowsNullPointerExceptionForNullType() {
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, config -> TEST_COMPONENT));

            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class,
                         () -> testSubject.componentRegistry(
                                 cr -> cr.registerDecorator(null, 42, (c, name, delegate) -> delegate)
                         )
            );
        }

        @Test
        void registerDecoratorThrowsIllegalArgumentExceptionForNullName() {
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, config -> TEST_COMPONENT));

            //noinspection DataFlowIssue
            assertThrows(IllegalArgumentException.class,
                         () -> testSubject.componentRegistry(
                                 cr -> cr.registerDecorator(Object.class, null, 42, (c, name, delegate) -> delegate)
                         )
            );
        }

        @Test
        void registerDecoratorThrowsNullPointerExceptionForNullComponentDecorator() {
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, config -> TEST_COMPONENT));

            //noinspection DataFlowIssue
            assertThrows(
                    NullPointerException.class,
                    () -> testSubject.componentRegistry(cr -> cr.registerDecorator(TestComponent.class, 42, null))
            );
        }
    }

    @Nested
    class EnhancerRegistration {

        @Test
        void registerEnhancerThrowsNullPointerExceptionForNullEnhancer() {
            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class,
                         () -> testSubject.componentRegistry(cr -> cr.registerEnhancer(null)));
        }

        @Test
        void registeredEnhancersAreInvokedDuringBuild() {
            AtomicBoolean invoked = new AtomicBoolean(false);

            testSubject.componentRegistry(cr -> cr.registerEnhancer(configurer -> invoked.set(true)));

            buildConfiguration();

            assertTrue(invoked.get());
        }

        @Test
        void registeredEnhancersAreInvokedDuringBuildOnlyOnce() {
            AtomicInteger counter = new AtomicInteger(0);

            testSubject.componentRegistry(cr -> cr.registerEnhancer(configurer -> counter.getAndIncrement()));
            // First build
            buildConfiguration();
            // Second build
            buildConfiguration();

            assertEquals(1, counter.get());
        }

        @Test
        void registeredEnhancersAreInvokedBasedOnDefinedOrder() {
            ConfigurationEnhancer enhancerWithLowOrder = spy(new ConfigurationEnhancer() {

                @Override
                public void enhance(@NonNull ComponentRegistry registry) {
                    // Not important, so do nothing.
                }

                @Override
                public int order() {
                    return -42;
                }
            });

            //noinspection Convert2Lambda - Cannot be lambda, as spying doesn't work otherwise.
            ConfigurationEnhancer enhancerWithDefaultOrder = spy(new ConfigurationEnhancer() {
                @Override
                public void enhance(@NonNull ComponentRegistry registry) {
                    // Not important, so do nothing.
                }
            });

            ConfigurationEnhancer enhancerWithHighOrder = spy(new ConfigurationEnhancer() {

                @Override
                public void enhance(@NonNull ComponentRegistry registry) {
                    // Not important, so do nothing.
                }

                @Override
                public int order() {
                    return 42;
                }
            });
            testSubject.componentRegistry(cr -> cr.registerEnhancer(enhancerWithDefaultOrder)
                                                  .registerEnhancer(enhancerWithHighOrder)
                                                  .registerEnhancer(enhancerWithLowOrder));

            buildConfiguration();

            InOrder enhancementOrder = inOrder(enhancerWithLowOrder, enhancerWithDefaultOrder, enhancerWithHighOrder);
            enhancementOrder.verify(enhancerWithLowOrder).enhance(any());
            enhancementOrder.verify(enhancerWithDefaultOrder).enhance(any());
            enhancementOrder.verify(enhancerWithHighOrder).enhance(any());
        }

        @Test
        void registeredEnhancersCanAddComponents() {
            testSubject.componentRegistry(cr -> cr.registerEnhancer(configurer -> configurer.registerComponent(
                    TestComponent.class, c -> TEST_COMPONENT
            )));

            Configuration config = buildConfiguration();

            assertEquals(TEST_COMPONENT, config.getComponent(TestComponent.class));
        }

        @Test
        void registeredEnhancersCanDecorateComponents() {
            TestComponent expected = new TestComponent(TEST_COMPONENT.state() + "-decorated");
            ConfigurationEnhancer enhancer = configurer -> configurer.registerDecorator(
                    TestComponent.class, 0, (c, name, delegate) -> new TestComponent(delegate.state() + "-decorated")
            );
            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, c -> TEST_COMPONENT)
                                                  .registerEnhancer(enhancer));

            Configuration config = buildConfiguration();

            assertEquals(expected, config.getComponent(TestComponent.class));
        }

        @Test
        void registeredEnhancersCanReplaceComponents() {
            Assumptions.assumeTrue(supportsOverriding(), "Ignore test since Component overriding is not supported.");

            TestComponent expected = new TestComponent("replacement");

            testSubject.componentRegistry(
                    cr -> cr.registerComponent(TestComponent.class, c -> TEST_COMPONENT)
                            .registerEnhancer(configurer -> configurer.registerComponent(
                                    TestComponent.class,
                                    c -> expected
                            ))
            );

            Configuration config = buildConfiguration();

            assertNotEquals(TEST_COMPONENT, config.getComponent(TestComponent.class));
            assertEquals(expected, config.getComponent(TestComponent.class));
        }

        @Test
        void registeredEnhancersCanReplaceComponentsConditionally() {
            TestComponent expected = new TestComponent("conditional");

            testSubject.componentRegistry(cr -> cr.registerComponent(TestComponent.class, c -> TEST_COMPONENT)
                                                  .registerEnhancer(configurer -> {
                                                      if (configurer.hasComponent(TestComponent.class)) {
                                                          configurer.registerComponent(TestComponent.class,
                                                                                       "conditional",
                                                                                       c -> expected);
                                                      }
                                                  }));

            Configuration config = buildConfiguration();

            assertEquals(TEST_COMPONENT, config.getComponent(TestComponent.class));
            assertEquals(expected, config.getComponent(TestComponent.class, "conditional"));
        }

        @Test
        void registeringSameEnhancerTypeSeveralTimesWillReplacePreviousRegistration() {
            AtomicBoolean firstRegistration = new AtomicBoolean(false);
            AtomicBoolean secondRegistration = new AtomicBoolean(false);
            AtomicBoolean thirdRegistration = new AtomicBoolean(false);

            testSubject.componentRegistry(
                    cr -> cr.registerEnhancer(new TestConfigurationEnhancer(firstRegistration))
                            .registerEnhancer(new TestConfigurationEnhancer(secondRegistration))
                            .registerEnhancer(new TestConfigurationEnhancer(thirdRegistration))
            );

            buildConfiguration();

            assertFalse(firstRegistration.get());
            assertFalse(secondRegistration.get());
            assertTrue(thirdRegistration.get());
        }

        @Test
        void enhancerCanRegisterAnotherEnhancer() {
            // given...
            AtomicBoolean firstEnhancerInvoked = new AtomicBoolean(false);
            AtomicBoolean secondEnhancerInvoked = new AtomicBoolean(false);

            ConfigurationEnhancer secondEnhancer = registry -> {
                secondEnhancerInvoked.set(true);
                registry.registerComponent(TestComponent.class, "second", c -> new TestComponent("second"));
            };

            ConfigurationEnhancer firstEnhancer = registry -> {
                firstEnhancerInvoked.set(true);
                registry.registerComponent(TestComponent.class, "first", c -> new TestComponent("first"));
                registry.registerEnhancer(secondEnhancer);
            };

            testSubject.componentRegistry(cr -> cr.registerEnhancer(firstEnhancer));

            // when...
            Configuration config = buildConfiguration();

            // then...
            assertTrue(firstEnhancerInvoked.get());
            assertTrue(secondEnhancerInvoked.get());
            assertEquals(new TestComponent("first"), config.getComponent(TestComponent.class, "first"));
            assertEquals(new TestComponent("second"), config.getComponent(TestComponent.class, "second"));
        }

        @Test
        void dynamicallyRegisteredEnhancersAreInvokedInCorrectOrder() {
            // given...
            AtomicInteger executionOrder = new AtomicInteger(0);
            AtomicInteger enhancerAOrder = new AtomicInteger(-1);
            AtomicInteger enhancerBOrder = new AtomicInteger(-1);
            AtomicInteger enhancerCOrder = new AtomicInteger(-1);

            ConfigurationEnhancer enhancerC = new ConfigurationEnhancer() {
                @Override
                public void enhance(@NonNull ComponentRegistry registry) {
                    enhancerCOrder.set(executionOrder.getAndIncrement());
                    registry.registerComponent(TestComponent.class, "C", c -> new TestComponent("C"));
                }

                @Override
                public int order() {
                    return 5;
                }
            };

            ConfigurationEnhancer enhancerB = new ConfigurationEnhancer() {
                @Override
                public void enhance(@NonNull ComponentRegistry registry) {
                    enhancerBOrder.set(executionOrder.getAndIncrement());
                    registry.registerComponent(TestComponent.class, "B", c -> new TestComponent("B"));
                }

                @Override
                public int order() {
                    return 10;
                }
            };

            ConfigurationEnhancer enhancerA = new ConfigurationEnhancer() {
                @Override
                public void enhance(@NonNull ComponentRegistry registry) {
                    enhancerAOrder.set(executionOrder.getAndIncrement());
                    registry.registerComponent(TestComponent.class, "A", c -> new TestComponent("A"));
                    // Register enhancerC with order=5, which should be invoked before enhancerB (order=10)
                    registry.registerEnhancer(enhancerC);
                }

                @Override
                public int order() {
                    return 0;
                }
            };

            testSubject.componentRegistry(cr -> cr.registerEnhancer(enhancerA).registerEnhancer(enhancerB));

            // when...
            Configuration config = buildConfiguration();

            // then...
            // Verify all components were created
            assertEquals(new TestComponent("A"), config.getComponent(TestComponent.class, "A"));
            assertEquals(new TestComponent("B"), config.getComponent(TestComponent.class, "B"));
            assertEquals(new TestComponent("C"), config.getComponent(TestComponent.class, "C"));

            // Verify execution order: A(0) -> C(5) -> B(10)
            assertEquals(0, enhancerAOrder.get(), "EnhancerA (order=0) should execute first");
            assertEquals(1, enhancerCOrder.get(), "EnhancerC (order=5) should execute second");
            assertEquals(2, enhancerBOrder.get(), "EnhancerB (order=10) should execute third");
        }

        @Test
        void dynamicallyRegisteredEnhancerWithLowerOrderThanParentExecutesAfterParent() {
            // given...
            AtomicInteger executionOrder = new AtomicInteger(0);
            AtomicInteger parentEnhancerOrder = new AtomicInteger(-1);
            AtomicInteger childEnhancerOrder = new AtomicInteger(-1);

            ConfigurationEnhancer childEnhancer = new ConfigurationEnhancer() {
                @Override
                public void enhance(@NonNull ComponentRegistry registry) {
                    childEnhancerOrder.set(executionOrder.getAndIncrement());
                    registry.registerComponent(TestComponent.class, "child", c -> new TestComponent("child"));
                }

                @Override
                public int order() {
                    return 5; // Lower order than parent
                }
            };

            ConfigurationEnhancer parentEnhancer = new ConfigurationEnhancer() {
                @Override
                public void enhance(@NonNull ComponentRegistry registry) {
                    parentEnhancerOrder.set(executionOrder.getAndIncrement());
                    registry.registerComponent(TestComponent.class, "parent", c -> new TestComponent("parent"));
                    // Register child with order=5, which is lower than parent's order=10
                    // But child should still execute AFTER parent since parent already executed
                    registry.registerEnhancer(childEnhancer);
                }

                @Override
                public int order() {
                    return 10; // Higher order than child
                }
            };

            testSubject.componentRegistry(cr -> cr.registerEnhancer(parentEnhancer));

            // when...
            Configuration config = buildConfiguration();

            // then...
            // Verify both components were created
            assertEquals(new TestComponent("parent"), config.getComponent(TestComponent.class, "parent"));
            assertEquals(new TestComponent("child"), config.getComponent(TestComponent.class, "child"));

            // Verify execution order: parent executes first, then child
            // Even though child has lower order value (5 < 10), it executes after parent
            // because it was registered dynamically during parent's execution
            assertEquals(0, parentEnhancerOrder.get(), "Parent enhancer (order=10) should execute first");
            assertEquals(1, childEnhancerOrder.get(), "Child enhancer (order=5) should execute second, after parent");
        }

        @Nested
        class DisableEnhancer {

            @Test
            void disableEnhancerByClassCanDisableFutureEnhancers() {
                // given...
                AtomicBoolean firstEnhancerInvoked = new AtomicBoolean(false);
                AtomicBoolean secondEnhancerInvoked = new AtomicBoolean(false);

                class SecondEnhancer implements ConfigurationEnhancer {

                    @Override
                    public void enhance(@NonNull ComponentRegistry registry) {
                        secondEnhancerInvoked.set(true);
                        registry.registerComponent(TestComponent.class, "second", c -> new TestComponent("second"));
                    }

                    @Override
                    public int order() {
                        return 100; // Execute after FirstEnhancer
                    }
                }

                class FirstEnhancer implements ConfigurationEnhancer {

                    @Override
                    public void enhance(@NonNull ComponentRegistry registry) {
                        firstEnhancerInvoked.set(true);
                        registry.registerComponent(TestComponent.class, "first", c -> new TestComponent("first"));
                        // Disable SecondEnhancer which hasn't executed yet
                        registry.disableEnhancer(SecondEnhancer.class);
                    }
                }

                testSubject.componentRegistry(cr -> cr.registerEnhancer(new FirstEnhancer())
                                                      .registerEnhancer(new SecondEnhancer()));

                // when...
                Configuration config = buildConfiguration();

                // then...
                // FirstEnhancer should execute, but SecondEnhancer should be disabled
                assertTrue(firstEnhancerInvoked.get(), "FirstEnhancer should execute");
                assertFalse(secondEnhancerInvoked.get(),
                            "SecondEnhancer should NOT execute because it was disabled");
                // Only first component should be registered
                assertEquals(new TestComponent("first"), config.getComponent(TestComponent.class, "first"));
                assertFalse(config.getOptionalComponent(TestComponent.class, "second").isPresent(),
                            "SecondEnhancer's component should not exist");
            }

            @Test
            void disableEnhancerByStringCanDisableFutureEnhancers() {
                // given...
                AtomicBoolean disablingEnhancerInvoked = new AtomicBoolean(false);
                AtomicBoolean targetEnhancerInvoked = new AtomicBoolean(false);

                class TargetEnhancer implements ConfigurationEnhancer {

                    @Override
                    public void enhance(@NonNull ComponentRegistry registry) {
                        targetEnhancerInvoked.set(true);
                        registry.registerComponent(TestComponent.class, "target", c -> new TestComponent("target"));
                    }

                    @Override
                    public int order() {
                        return 100; // Execute after disabling enhancer
                    }
                }

                ConfigurationEnhancer disablingEnhancer = registry -> {
                    disablingEnhancerInvoked.set(true);
                    registry.registerComponent(TestComponent.class, "disabler", c -> new TestComponent("disabler"));
                    // Disable TargetEnhancer by class name
                    registry.disableEnhancer(TargetEnhancer.class.getName());
                };

                testSubject.componentRegistry(cr -> cr.registerEnhancer(disablingEnhancer)
                                                      .registerEnhancer(new TargetEnhancer()));

                // when...
                Configuration config = buildConfiguration();

                // then...
                // Disabling enhancer should execute, but target should be disabled
                assertTrue(disablingEnhancerInvoked.get(), "Disabling enhancer should execute");
                assertFalse(targetEnhancerInvoked.get(),
                            "TargetEnhancer should NOT execute because it was disabled by name");
                // Only disabler component should be registered
                assertEquals(new TestComponent("disabler"), config.getComponent(TestComponent.class, "disabler"));
                assertFalse(config.getOptionalComponent(TestComponent.class, "target").isPresent(),
                            "TargetEnhancer's component should not exist");
            }

            @Test
            void disableEnhancerWhenEnhancerWithLowerOrderDisablesHigherOrder() {
                // given...
                AtomicInteger executionOrder = new AtomicInteger(0);
                AtomicInteger lowOrderEnhancerOrder = new AtomicInteger(-1);
                AtomicInteger highOrderEnhancerOrder = new AtomicInteger(-1);

                class HighOrderEnhancer implements ConfigurationEnhancer {

                    @Override
                    public void enhance(@NonNull ComponentRegistry registry) {
                        highOrderEnhancerOrder.set(executionOrder.getAndIncrement());
                        registry.registerComponent(TestComponent.class, "high", c -> new TestComponent("high"));
                    }

                    @Override
                    public int order() {
                        return 100; // Higher order, executes later
                    }
                }

                class LowOrderEnhancer implements ConfigurationEnhancer {

                    @Override
                    public void enhance(@NonNull ComponentRegistry registry) {
                        lowOrderEnhancerOrder.set(executionOrder.getAndIncrement());
                        registry.registerComponent(TestComponent.class, "low", c -> new TestComponent("low"));
                        // Disable an enhancer that hasn't executed yet
                        registry.disableEnhancer(HighOrderEnhancer.class);
                    }

                    @Override
                    public int order() {
                        return 10; // Lower order, executes first
                    }
                }

                testSubject.componentRegistry(cr -> cr.registerEnhancer(new HighOrderEnhancer())
                                                      .registerEnhancer(new LowOrderEnhancer()));

                // when...
                Configuration config = buildConfiguration();

                // then...
                // Verify only LowOrderEnhancer executed
                assertEquals(0, lowOrderEnhancerOrder.get(),
                             "LowOrderEnhancer (order=10) should execute first");
                assertEquals(-1, highOrderEnhancerOrder.get(),
                             "HighOrderEnhancer (order=100) should NOT execute because it was disabled");
                // Only low component should be registered
                assertEquals(new TestComponent("low"), config.getComponent(TestComponent.class, "low"));
                assertFalse(config.getOptionalComponent(TestComponent.class, "high").isPresent(),
                            "HighOrderEnhancer's component should not exist");
            }

            @Test
            void disableEnhancerCannotDisableAlreadyExecutedEnhancer() {
                // given...
                AtomicInteger executionOrder = new AtomicInteger(0);
                AtomicInteger lowOrderEnhancerOrder = new AtomicInteger(-1);
                AtomicInteger highOrderEnhancerOrder = new AtomicInteger(-1);

                class LowOrderEnhancer implements ConfigurationEnhancer {

                    @Override
                    public void enhance(@NonNull ComponentRegistry registry) {
                        lowOrderEnhancerOrder.set(executionOrder.getAndIncrement());
                        registry.registerComponent(TestComponent.class, "low", c -> new TestComponent("low"));
                    }

                    @Override
                    public int order() {
                        return 10; // Lower order, executes first
                    }
                }

                class HighOrderEnhancer implements ConfigurationEnhancer {

                    @Override
                    public void enhance(@NonNull ComponentRegistry registry) {
                        highOrderEnhancerOrder.set(executionOrder.getAndIncrement());
                        registry.registerComponent(TestComponent.class, "high", c -> new TestComponent("high"));
                        // Try to disable an enhancer that already executed
                        // This has no effect as it already ran
                        registry.disableEnhancer(LowOrderEnhancer.class);
                    }

                    @Override
                    public int order() {
                        return 100; // Higher order, executes later
                    }
                }

                testSubject.componentRegistry(cr -> cr.registerEnhancer(new LowOrderEnhancer())
                                                      .registerEnhancer(new HighOrderEnhancer()));

                // when...
                Configuration config = buildConfiguration();

                // then...
                // Both enhancers should have executed (can't disable already-executed enhancer)
                assertEquals(0, lowOrderEnhancerOrder.get(),
                             "LowOrderEnhancer (order=10) should execute first");
                assertEquals(1, highOrderEnhancerOrder.get(),
                             "HighOrderEnhancer (order=100) should execute second");
                // Both components should be registered, proving LowOrderEnhancer executed and wasn't retroactively disabled
                assertEquals(new TestComponent("low"), config.getComponent(TestComponent.class, "low"));
                assertEquals(new TestComponent("high"), config.getComponent(TestComponent.class, "high"));
            }

            @Test
            void disableEnhancerMultipleTimes() {
                // given...
                AtomicBoolean firstEnhancerInvoked = new AtomicBoolean(false);
                AtomicBoolean targetEnhancerInvoked = new AtomicBoolean(false);
                AtomicInteger disablerInvocationCount = new AtomicInteger(0);

                class TargetEnhancer implements ConfigurationEnhancer {

                    @Override
                    public void enhance(@NonNull ComponentRegistry registry) {
                        targetEnhancerInvoked.set(true);
                        registry.registerComponent(TestComponent.class, "target", c -> new TestComponent("target"));
                    }

                    @Override
                    public int order() {
                        return 100; // Execute after others
                    }
                }

                ConfigurationEnhancer firstEnhancer = registry -> {
                    firstEnhancerInvoked.set(true);
                    disablerInvocationCount.incrementAndGet();
                    // Disable multiple times - should work the same
                    registry.disableEnhancer(TargetEnhancer.class);
                    registry.disableEnhancer(TargetEnhancer.class.getName());
                    registry.disableEnhancer(TargetEnhancer.class);
                    registry.registerComponent(TestComponent.class, "first", c -> new TestComponent("first"));
                };

                testSubject.componentRegistry(cr -> cr.registerEnhancer(firstEnhancer)
                                                      .registerEnhancer(new TargetEnhancer()));

                // when...
                Configuration config = buildConfiguration();

                // then...
                // First enhancer executes, target should be disabled despite multiple disable calls
                assertTrue(firstEnhancerInvoked.get(), "First enhancer should execute");
                assertFalse(targetEnhancerInvoked.get(),
                            "TargetEnhancer should NOT execute (disabled by multiple calls)");
                assertEquals(1, disablerInvocationCount.get(), "First enhancer should execute exactly once");
                // Only first component should be registered
                assertEquals(new TestComponent("first"), config.getComponent(TestComponent.class, "first"));
                assertFalse(config.getOptionalComponent(TestComponent.class, "target").isPresent(),
                            "TargetEnhancer's component should not exist");
            }

            @Test
            void disableEnhancerWithNonExistentClassNameCompletesNormally() {
                // given...
                AtomicBoolean enhancerInvoked = new AtomicBoolean(false);

                ConfigurationEnhancer enhancer = registry -> {
                    enhancerInvoked.set(true);
                    // Try to disable a non-existent enhancer class - should not throw exception
                    registry.disableEnhancer("com.nonexistent.FakeEnhancer");
                    registry.disableEnhancer("another.fake.EnhancerClass");
                    registry.registerComponent(TestComponent.class, "component", c -> new TestComponent("component"));
                };

                testSubject.componentRegistry(cr -> cr.registerEnhancer(enhancer));

                // when...
                Configuration config = buildConfiguration();

                // then...
                // Should complete normally without throwing exceptions
                assertTrue(enhancerInvoked.get(), "Enhancer should execute normally");
                assertEquals(new TestComponent("component"), config.getComponent(TestComponent.class, "component"));
            }
        }

        @Test
        void getComponentsReturnsEnhancerRegisteredComponents() {
            // given
            TestComponent enhancerComponent = new TestComponent("enhancer-registered");
            testSubject.componentRegistry(cr -> cr.registerEnhancer(registry -> registry.registerComponent(
                    TestComponent.class, "enhancer-component", c -> enhancerComponent
            )));

            // when
            Configuration config = buildConfiguration();
            Map<String, TestComponent> result = config.getComponents(TestComponent.class);

            // then
            assertEquals(1, result.size());
            assertTrue(result.containsKey("enhancer-component"));
            assertSame(enhancerComponent, result.get("enhancer-component"),
                       "Components registered by enhancers should be findable via getComponents()");
        }

        record TestConfigurationEnhancer(AtomicBoolean invoked) implements ConfigurationEnhancer {

            @Override
            public void enhance(@NonNull ComponentRegistry registry) {
                invoked.set(true);
            }
        }
    }

    @Nested
    class ModuleRegistration {

        @Test
        void registerModuleThrowsNullPointerExceptionForNullModuleBuilder() {
            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class,
                         () -> testSubject.componentRegistry(cr -> cr.registerModule(null)));
        }

        @Test
        void registerModuleExposesModulesConfigurationsUponBuild() {
            testSubject.componentRegistry(cr -> cr.registerModule(new TestModule("one"))
                                                  .registerModule(new TestModule("two")));

            AxonConfiguration configuration = buildConfiguration();
            List<Configuration> result = configuration.getModuleConfigurations();

            assertEquals(2, result.size());

            assertTrue(configuration.getModuleConfiguration("one").isPresent());
            assertTrue(configuration.getModuleConfiguration("two").isPresent());
            assertFalse(configuration.getModuleConfiguration("three").isPresent());
        }

        @Test
        void registeringModuleWithExistingNameIsRejected() {
            testSubject.componentRegistry(cr -> cr.registerModule(new TestModule("one"))
                                                  .registerModule(new TestModule("two")));

            assertThrows(DuplicateModuleRegistrationException.class,
                         () -> testSubject.componentRegistry(cr -> cr.registerModule(new TestModule("two"))));
        }

        @Test
        void canRetrieveComponentsFromModuleAndParentOnly() {
            TestComponent rootComponent = new TestComponent("root");
            TestComponent levelOneModuleComponent = new TestComponent("root-one");
            TestComponent levelTwoModuleComponent = new TestComponent("root-two");

            testSubject.componentRegistry(
                    cr -> {
                        cr.registerComponent(TestComponent.class, "root", rootConfig -> rootComponent);
                        cr.registerModule(
                                new TestModule("one").componentRegistry(
                                        mcr -> {
                                            mcr.registerComponent(
                                                    TestComponent.class, "one",
                                                    c -> c.getOptionalComponent(TestComponent.class, "root")
                                                          .map(delegate -> new TestComponent(delegate.state + "-one"))
                                                          .orElseThrow()
                                            );
                                            mcr.registerModule(
                                                    new TestModule("two").componentRegistry(
                                                            cr2 -> cr2.registerComponent(
                                                                    TestComponent.class, "two",
                                                                    c -> c.getOptionalComponent(
                                                                                  TestComponent.class, "root"
                                                                          )
                                                                          .map(delegate -> new TestComponent(
                                                                                  delegate.state + "-two"
                                                                          ))
                                                                          .orElseThrow())));
                                        })
                        );
                    }
            );

            // Root configurer outcome only has own components.
            Configuration rootConfig = buildConfiguration();
            assertEquals(rootComponent, rootConfig.getComponent(TestComponent.class, "root"));
            assertFalse(rootConfig.getOptionalComponent(TestComponent.class, "one").isPresent());
            assertFalse(rootConfig.getOptionalComponent(TestComponent.class, "two").isPresent());
            // Level one module outcome has own components and access to parent.
            List<Configuration> levelOneConfigurations = rootConfig.getModuleConfigurations();
            assertThat(levelOneConfigurations).hasSize(1);
            Configuration levelOneConfig = levelOneConfigurations.getFirst();
            assertThat(levelOneConfig.getOptionalComponent(TestComponent.class, "root")).isPresent();
            assertEquals(levelOneModuleComponent, levelOneConfig.getComponent(TestComponent.class, "one"));
            assertFalse(levelOneConfig.getOptionalComponent(TestComponent.class, "two").isPresent());
            // Level two module outcome has own components and access to parent, and parent's parent.
            List<Configuration> levelTwoConfigurations = levelOneConfig.getModuleConfigurations();
            assertThat(levelTwoConfigurations).hasSize(1);
            Configuration levelTwoConfig = levelTwoConfigurations.getFirst();
            assertTrue(levelTwoConfig.getOptionalComponent(TestComponent.class, "root").isPresent());
            assertTrue(levelTwoConfig.getOptionalComponent(TestComponent.class, "one").isPresent());
            assertEquals(levelTwoModuleComponent, levelTwoConfig.getComponent(TestComponent.class, "two"));
        }

        @Test
        void cannotRetrieveComponentsRegisteredFromModulesRegisteredOnTheSameLevel() {
            TestComponent rootComponent = new TestComponent("root");
            TestComponent leftModuleComponent = new TestComponent("left");
            TestComponent rightModuleComponent = new TestComponent("right");
            testSubject.componentRegistry(
                    cr -> cr.registerComponent(TestComponent.class, "root", rootConfig -> rootComponent)
                            .registerModule(new TestModule("left").componentRegistry(
                                    mcr -> mcr.registerComponent(TestComponent.class, "left", c -> leftModuleComponent)
                            ))
                            .registerModule(new TestModule("right").componentRegistry(
                                    mcr -> mcr.registerComponent(TestComponent.class, "right",
                                                                 c -> rightModuleComponent)
                            ))
            );

            // Root configurer outcome only has own components.
            Configuration rootConfig = buildConfiguration();
            assertEquals(rootComponent, rootConfig.getComponent(TestComponent.class, "root"));
            assertFalse(rootConfig.getOptionalComponent(TestComponent.class, "one").isPresent());
            assertFalse(rootConfig.getOptionalComponent(TestComponent.class, "two").isPresent());
            List<Configuration> levelOneConfigurations = rootConfig.getModuleConfigurations();
            assertEquals(2, levelOneConfigurations.size());
            // Left module can access own components and parent, not its siblings.
            Configuration leftConfig = levelOneConfigurations.getFirst();
            assertTrue(leftConfig.getOptionalComponent(TestComponent.class, "root").isPresent());
            assertEquals(leftModuleComponent, leftConfig.getComponent(TestComponent.class, "left"));
            assertFalse(leftConfig.getOptionalComponent(TestComponent.class, "right").isPresent());
            // Right module can access own components and parent, not its siblings,
            Configuration rightConfig = levelOneConfigurations.get(1);
            assertTrue(rightConfig.getOptionalComponent(TestComponent.class, "root").isPresent());
            assertEquals(rightModuleComponent, rightConfig.getComponent(TestComponent.class, "right"));
            assertFalse(rightConfig.getOptionalComponent(TestComponent.class, "left").isPresent());
        }

        @Test
        void decoratingIsInheritedAcrossLevels() {
            String expectedRootComponentState = "root-decorated-by-root";
            String expectedLevelOneComponentState = "level-one-decorated-by-root-decorated-by-level-one";
            String expectedLevelTwoComponentState = "level-two-decorated-by-root-decorated-by-level-one-decorated-by-level-two";
            testSubject.componentRegistry(
                    cr -> cr.registerComponent(TestComponent.class,
                                               rootConfig -> new TestComponent("root"))
                            .registerDecorator(
                                    TestComponent.class, 0,
                                    (rootConfig, name, delegate) -> new TestComponent(
                                            delegate.state() + "-decorated-by-root"
                                    )
                            )
                            .registerModule(
                                    new TestModule("level-one").componentRegistry(
                                            mcr -> mcr.registerComponent(
                                                              TestComponent.class,
                                                              c -> new TestComponent("level-one")
                                                      )
                                                      .registerDecorator(
                                                              TestComponent.class, 1,
                                                              (config, name, delegate) -> new TestComponent(
                                                                      delegate.state() + "-decorated-by-level-one"
                                                              )
                                                      )
                                                      .registerModule(
                                                              new TestModule("level-two").componentRegistry(
                                                                      emcr -> emcr.registerComponent(
                                                                                          TestComponent.class,
                                                                                          config -> new TestComponent(
                                                                                                  "level-two"
                                                                                          )
                                                                                  )
                                                                                  .registerDecorator(
                                                                                          TestComponent.class, 2,
                                                                                          (config, name, delegate) -> new TestComponent(
                                                                                                  delegate.state()
                                                                                                          + "-decorated-by-level-two")
                                                                                  )
                                                              )
                                                      )
                                    )
                            )
            );

            // Check decoration on root level.
            Configuration root = buildConfiguration();
            assertEquals(expectedRootComponentState, root.getComponent(TestComponent.class).state());
            assertNotEquals(expectedLevelOneComponentState, root.getComponent(TestComponent.class).state());
            assertNotEquals(expectedLevelTwoComponentState, root.getComponent(TestComponent.class).state());
            // Check decoration on level one.
            List<Configuration> rootModuleConfigs = root.getModuleConfigurations();
            assertThat(rootModuleConfigs).hasSize(1);
            Configuration levelOne = rootModuleConfigs.getFirst();
            assertNotEquals(expectedRootComponentState, levelOne.getComponent(TestComponent.class).state());
            assertEquals(expectedLevelOneComponentState, levelOne.getComponent(TestComponent.class).state());
            assertNotEquals(expectedLevelTwoComponentState, levelOne.getComponent(TestComponent.class).state());
            // Check decoration on level two.
            List<Configuration> levelOneConfigs = levelOne.getModuleConfigurations();
            assertThat(levelOneConfigs).hasSize(1);
            Configuration levelTwo = levelOneConfigs.getFirst();
            assertNotEquals(expectedRootComponentState, levelTwo.getComponent(TestComponent.class).state());
            assertNotEquals(expectedLevelOneComponentState, levelTwo.getComponent(TestComponent.class).state());
            assertEquals(expectedLevelTwoComponentState, levelTwo.getComponent(TestComponent.class).state());
        }

        @Test
        void getComponentWithDefaultChecksCurrentModuleAndParent() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            TestComponent defaultComponent = new TestComponent("default");
            TestComponent registeredComponent = TEST_COMPONENT;
            testSubject.componentRegistry(
                    cr -> cr.registerModule(new TestModule("test-module").componentRegistry(
                            mcr -> mcr.registerComponent(TestComponent.class, "id", c -> registeredComponent))
                    )
            );

            Configuration rootConfig = buildConfiguration();

            TestComponent result = rootConfig.getComponent(TestComponent.class, "id", () -> {
                invoked.set(true);
                return defaultComponent;
            });

            assertTrue(invoked.get());
            assertEquals(defaultComponent, result);
            assertNotEquals(registeredComponent, result);

            invoked.set(false);
            List<Configuration> levelOneConfigs = rootConfig.getModuleConfigurations();
            assertEquals(1, levelOneConfigs.size());
            Configuration levelOneConfig = levelOneConfigs.getFirst();
            result = levelOneConfig.getComponent(TestComponent.class, "id", () -> {
                invoked.set(true);
                return defaultComponent;
            });

            assertFalse(invoked.get());
            assertNotEquals(defaultComponent, result);
            assertEquals(registeredComponent, result);
        }

        @Test
        void getComponentsReturnsComponentsFromModules() {
            // given
            TestComponent rootComponent = new TestComponent("root");
            TestComponent moduleComponent = new TestComponent("module");

            testSubject.componentRegistry(
                    cr -> cr.registerComponent(TestComponent.class, "root", c -> rootComponent)
                            .registerModule(new TestModule("test-module").componentRegistry(
                                    mcr -> mcr.registerComponent(TestComponent.class, "module", c -> moduleComponent)
                            ))
            );

            // when
            Configuration config = buildConfiguration();
            Map<String, TestComponent> result = config.getComponents(TestComponent.class);

            // then
            assertEquals(2, result.size(), "Should include components from both root and modules");
            assertTrue(result.containsKey("root"));
            assertTrue(result.containsKey("module"));
            assertSame(rootComponent, result.get("root"));
            assertSame(moduleComponent, result.get("module"));
        }
    }

    @Nested
    class FactoryRegistration {

        @Test
        void factoryIsNotConsultedWhenComponentForTypeAndNameIsAlreadyPresent() {
            Assumptions.assumeTrue(supportsComponentFactories(),
                                   "Ignore test since ComponentFactories are not supported.");

            TestComponent expectedComponent = new TestComponent("state");
            TestComponent expectedNamedComponent = new TestComponent("named");
            TestComponentFactory testFactory = spy(new TestComponentFactory("constructed"));

            testSubject.componentRegistry(
                    registry -> registry.registerComponent(TestComponent.class, c -> expectedComponent)
                                        .registerComponent(TestComponent.class, "name", c -> expectedNamedComponent)
                                        .registerFactory(testFactory)
            );

            AxonConfiguration config = buildConfiguration();

            assertEquals(expectedComponent, config.getComponent(TestComponent.class));
            assertEquals(expectedNamedComponent, config.getComponent(TestComponent.class, "name"));
            verify(testFactory).registerShutdownHandlers(any());
            verifyNoMoreInteractions(testFactory);
        }

        @Test
        void factoryIsConsultedOnceWhenThereIsNoComponentForTypeAndName() {
            Assumptions.assumeTrue(supportsComponentFactories(),
                                   "Ignore test since ComponentFactories are not supported.");

            String expectedState = "constructed";
            TestComponentFactory testFactory = spy(new TestComponentFactory(expectedState));

            testSubject.componentRegistry(registry -> registry.registerFactory(testFactory));

            AxonConfiguration config = buildConfiguration();

            TestComponent resultComponent = config.getComponent(TestComponent.class, "first-instance");
            assertEquals(expectedState, resultComponent.state());
            // Second retrieval should return same object since first invocation should register the object.
            assertSame(resultComponent, config.getComponent(TestComponent.class, "first-instance"));
            verify(testFactory, times(1)).construct(any(), any());
            assertNotSame(resultComponent, config.getComponent(TestComponent.class, "second-instance"));
            verify(testFactory, times(2)).construct(any(), any());
        }

        @Test
        void factoryIsConsultedButMayReturnNothingWhenThereIsNoComponentForTypeAndName() {
            Assumptions.assumeTrue(supportsComponentFactories(),
                                   "Ignore test since ComponentFactories are not supported.");

            String expectedState = "constructed";
            TestComponentFactory testFactory = spy(new TestComponentFactory(expectedState, new AtomicBoolean(false)));

            testSubject.componentRegistry(registry -> registry.registerFactory(testFactory));

            AxonConfiguration config = buildConfiguration();

            assertFalse(config.getOptionalComponent(TestComponent.class).isPresent());
            assertFalse(config.getOptionalComponent(TestComponent.class).isPresent());
            assertFalse(config.getOptionalComponent(TestComponent.class, "name").isPresent());
            assertFalse(config.getOptionalComponent(TestComponent.class, "name").isPresent());
            // The ComponentFactory invocation is skipped when no name is given. Hence why we check for two invocations!
            verify(testFactory, times(2)).construct(any(), any());
        }

        @Test
        void getOrDefaultConsultsFactoryBeforeInvokingDefaultSupplier() {
            Assumptions.assumeTrue(supportsComponentFactories(),
                                   "Ignore test since ComponentFactories are not supported.");

            AtomicBoolean invoked = new AtomicBoolean(false);
            TestComponent expectedDefaultComponent = new TestComponent("default");
            String expectedFactoryState = "constructed";
            AtomicBoolean constructSwitch = new AtomicBoolean(true);
            TestComponentFactory testFactory = spy(new TestComponentFactory(expectedFactoryState, constructSwitch));

            testSubject.componentRegistry(registry -> registry.registerFactory(testFactory));

            AxonConfiguration config = buildConfiguration();

            TestComponent result = config.getComponent(TestComponent.class, "first", () -> {
                invoked.set(true);
                return expectedDefaultComponent;
            });
            assertFalse(invoked.get());
            assertNotEquals(expectedDefaultComponent, result);
            assertEquals(expectedFactoryState, result.state());
            // Turn the factory off. This should trigger the default supplier.
            constructSwitch.set(false);
            result = config.getComponent(TestComponent.class, "second", () -> {
                invoked.set(true);
                return expectedDefaultComponent;
            });
            assertTrue(invoked.get());
            assertEquals(expectedDefaultComponent, result);
            assertNotEquals(expectedFactoryState, result.state());
            verify(testFactory, times(2)).construct(any(), any());
        }

        @Test
        void getComponentsDoesNotIncludeFactoryComponentsNotYetAccessed() {
            Assumptions.assumeTrue(supportsComponentFactories(),
                                   "Ignore test since ComponentFactories are not supported.");

            // given
            TestComponent registeredComponent = new TestComponent("registered");
            TestComponentFactory testFactory = spy(new TestComponentFactory("factory-created"));

            testSubject.componentRegistry(
                    registry -> registry.registerComponent(TestComponent.class, "registered", c -> registeredComponent)
                                        .registerFactory(testFactory)
            );

            // when
            Configuration config = buildConfiguration();
            Map<String, TestComponent> result = config.getComponents(TestComponent.class);

            // then
            assertEquals(1, result.size(), "Only registered components should be included, not factory-created ones");
            assertTrue(result.containsKey("registered"));
            assertSame(registeredComponent, result.get("registered"));
            // Factory should not be consulted by getComponents()
            verify(testFactory).registerShutdownHandlers(any());
            verifyNoMoreInteractions(testFactory);
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void startLifecycleHandlersAreInvokedInAscendingPhaseOrder() {
            Assumptions.assumeTrue(
                    doesOwnLifecycleManagement(),
                    "Ignore test since lifecycle management is not managed by the ApplicationConfigurer itself."
            );

            LifecycleManagedInstance phaseZeroHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOneHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseTenHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOverNineThousandHandler = spy(new LifecycleManagedInstance());

            testSubject.lifecycleRegistry(lc -> lc.onStart(0, phaseZeroHandler::start));
            testSubject.lifecycleRegistry(lc -> lc.onStart(1, phaseOneHandler::start));
            testSubject.lifecycleRegistry(lc -> lc.onStart(10, phaseTenHandler::start));
            testSubject.lifecycleRegistry(lc -> lc.onStart(9001, phaseOverNineThousandHandler::start));

            testSubject.start();

            InOrder lifecycleOrder = inOrder(phaseZeroHandler,
                                             phaseOneHandler,
                                             phaseTenHandler,
                                             phaseOverNineThousandHandler);
            lifecycleOrder.verify(phaseZeroHandler).start();
            lifecycleOrder.verify(phaseOneHandler).start();
            lifecycleOrder.verify(phaseTenHandler).start();
            lifecycleOrder.verify(phaseOverNineThousandHandler).start();
        }

        @Test
        void startLifecycleHandlerConfiguredThroughConfigurerAreInvokedInAscendingPhaseOrder() {
            Assumptions.assumeTrue(
                    doesOwnLifecycleManagement(),
                    "Ignore test since lifecycle management is not managed by the ApplicationConfigurer itself."
            );

            LifecycleManagedInstance phaseZeroHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOneHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseTenHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOverNineThousandHandler = spy(new LifecycleManagedInstance());

            testSubject.lifecycleRegistry(lc -> lc.onStart(9001, phaseOverNineThousandHandler::start));
            testSubject.lifecycleRegistry(lc -> lc.onStart(10, phaseTenHandler::start));
            testSubject.lifecycleRegistry(lc -> lc.onStart(1, phaseOneHandler::start));
            testSubject.lifecycleRegistry(lc -> lc.onStart(0, phaseZeroHandler::start));

            testSubject.start();

            InOrder lifecycleOrder = inOrder(phaseZeroHandler,
                                             phaseOneHandler,
                                             phaseTenHandler,
                                             phaseOverNineThousandHandler);
            lifecycleOrder.verify(phaseZeroHandler).start();
            lifecycleOrder.verify(phaseOneHandler).start();
            lifecycleOrder.verify(phaseTenHandler).start();
            lifecycleOrder.verify(phaseOverNineThousandHandler).start();
        }

        // Suppress Thread.sleep, as it's mandatory for this test.
        @Test
        void startLifecycleHandlersWillOnlyProceedToFollowingPhaseAfterCurrentPhaseIsFinalized()
                throws InterruptedException {
            Assumptions.assumeTrue(
                    doesOwnLifecycleManagement(),
                    "Ignore test since lifecycle management is not managed by the ApplicationConfigurer itself."
            );

            // Create a lock for the slow handler and lock it immediately, to spoof the handler's slow/long process
            ReentrantLock slowHandlerLock = new ReentrantLock();
            slowHandlerLock.lock();

            LifecycleManagedInstance phaseZeroHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance slowPhaseZeroHandler = spy(new LifecycleManagedInstance(slowHandlerLock));
            LifecycleManagedInstance phaseOneHandler = spy(new LifecycleManagedInstance());

            testSubject.lifecycleRegistry(lc -> lc.onStart(0, phaseZeroHandler::start));
            testSubject.lifecycleRegistry(lc -> lc.onStart(0, slowPhaseZeroHandler::slowStart));
            testSubject.lifecycleRegistry(lc -> lc.onStart(1, phaseOneHandler::start));

            // Start in a different thread as the 'slowPhaseZeroHandler' will otherwise not lock
            Thread startThread = new Thread(() -> testSubject.start());
            startThread.start();

            try {
                // Phase one has not started yet, as the method has not been invoked yet.
                await().untilAsserted(() -> verify(phaseOneHandler, never()).start());
                // The phase zero handlers on the other hand have been invoked
                await().untilAsserted(() -> verify(phaseZeroHandler).start());
                await().untilAsserted(() -> verify(slowPhaseZeroHandler).slowStart());
            } finally {
                slowHandlerLock.unlock();
            }

            // Wait until the start thread is finished prior to validating the order.
            startThread.join();

            verify(phaseOneHandler).start();

            InOrder lifecycleOrder = inOrder(phaseZeroHandler, slowPhaseZeroHandler, phaseOneHandler);
            lifecycleOrder.verify(phaseZeroHandler).start();
            lifecycleOrder.verify(slowPhaseZeroHandler).slowStart();
            lifecycleOrder.verify(phaseOneHandler).start();
        }

        @Test
        void shutdownLifecycleHandlersAreInvokedInDescendingPhaseOrder() {
            Assumptions.assumeTrue(
                    doesOwnLifecycleManagement(),
                    "Ignore test since lifecycle management is not managed by the ApplicationConfigurer itself."
            );

            LifecycleManagedInstance phaseZeroHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOneHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseTenHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOverNineThousandHandler = spy(new LifecycleManagedInstance());

            testSubject.lifecycleRegistry(lc -> lc.onShutdown(9001, phaseOverNineThousandHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(10, phaseTenHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(1, phaseOneHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(0, phaseZeroHandler::shutdown));

            AxonConfiguration rootConfig = testSubject.start();
            rootConfig.shutdown();

            InOrder lifecycleOrder = inOrder(phaseOverNineThousandHandler,
                                             phaseTenHandler,
                                             phaseOneHandler,
                                             phaseZeroHandler);
            lifecycleOrder.verify(phaseOverNineThousandHandler).shutdown();
            lifecycleOrder.verify(phaseTenHandler).shutdown();
            lifecycleOrder.verify(phaseOneHandler).shutdown();
            lifecycleOrder.verify(phaseZeroHandler).shutdown();
        }

        @Test
        void shutdownLifecycleHandlersConfiguredThroughConfigurerAreInvokedInDescendingPhaseOrder() {
            Assumptions.assumeTrue(
                    doesOwnLifecycleManagement(),
                    "Ignore test since lifecycle management is not managed by the ApplicationConfigurer itself."
            );

            LifecycleManagedInstance phaseZeroHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOneHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseTenHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOverNineThousandHandler = spy(new LifecycleManagedInstance());

            testSubject.lifecycleRegistry(lc -> lc.onShutdown(0, phaseZeroHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(1, phaseOneHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(10, phaseTenHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(9001, phaseOverNineThousandHandler::shutdown));

            AxonConfiguration rootConfig = testSubject.start();
            rootConfig.shutdown();

            InOrder lifecycleOrder = inOrder(phaseOverNineThousandHandler,
                                             phaseTenHandler,
                                             phaseOneHandler,
                                             phaseZeroHandler);
            lifecycleOrder.verify(phaseOverNineThousandHandler).shutdown();
            lifecycleOrder.verify(phaseTenHandler).shutdown();
            lifecycleOrder.verify(phaseOneHandler).shutdown();
            lifecycleOrder.verify(phaseZeroHandler).shutdown();
        }

        // Suppress Thread.sleep, as it's mandatory for this test.
        @Test
        @SuppressWarnings("java:S2925")
        void shutdownLifecycleHandlersWillOnlyProceedToFollowingPhaseAfterCurrentPhaseIsFinalized()
                throws InterruptedException {
            Assumptions.assumeTrue(
                    doesOwnLifecycleManagement(),
                    "Ignore test since lifecycle management is not managed by the ApplicationConfigurer itself."
            );

            // Create a lock for the slow handler and lock it immediately, to spoof the handler's slow/long process
            ReentrantLock slowHandlerLock = new ReentrantLock();
            slowHandlerLock.lock();

            LifecycleManagedInstance phaseOneHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance slowPhaseOneHandler = spy(new LifecycleManagedInstance(slowHandlerLock));
            LifecycleManagedInstance phaseZeroHandler = spy(new LifecycleManagedInstance());

            testSubject.lifecycleRegistry(lc -> lc.onShutdown(1, phaseOneHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(1, slowPhaseOneHandler::slowShutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(0, phaseZeroHandler::shutdown));

            AxonConfiguration rootConfig = testSubject.start();

            // Start in a different thread as the 'slowPhaseOneHandler' will otherwise not lock
            Thread shutdownThread = new Thread(rootConfig::shutdown);
            shutdownThread.start();
            // Sleep to give the shutdown thread some time to execute
            Thread.sleep(250);

            try {
                // Phase one has not started yet, as the method has not been invoked yet.
                verify(phaseZeroHandler, never()).shutdown();
                // The phase zero handlers on the other hand have been invoked
                verify(phaseOneHandler).shutdown();
                verify(slowPhaseOneHandler).slowShutdown();
            } finally {
                slowHandlerLock.unlock();
            }

            // Wait until the shutdown-thread is finished prior to validating the order.
            shutdownThread.join();
            verify(phaseZeroHandler).shutdown();

            InOrder lifecycleOrder = inOrder(phaseOneHandler, slowPhaseOneHandler, phaseZeroHandler);
            lifecycleOrder.verify(phaseOneHandler).shutdown();
            lifecycleOrder.verify(slowPhaseOneHandler).slowShutdown();
            lifecycleOrder.verify(phaseZeroHandler).shutdown();
        }

        /**
         * To be honest, I don't know why somebody would add a start handler during shutdown, but since the validation
         * is there through the lifecycle state I wanted to test it regardless.
         */
        @Test
        void outOfOrderAddedStartHandlerDuringShutdownIsNotCalledImmediately() {
            Assumptions.assumeTrue(
                    doesOwnLifecycleManagement(),
                    "Ignore test since lifecycle management is not managed by the ApplicationConfigurer itself."
            );

            LifecycleManagedInstance phaseTwoHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOneHandlerAdder = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance addedPhaseOneStartHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOneHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseZeroHandler = spy(new LifecycleManagedInstance());

            testSubject.lifecycleRegistry(lc -> lc.onShutdown(2, phaseTwoHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(1,
                                                              () -> phaseOneHandlerAdder.addLifecycleHandler(
                                                                      LifecycleRegistry::onStart,
                                                                      lc,
                                                                      1,
                                                                      addedPhaseOneStartHandler::start)));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(1, phaseOneHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(0, phaseZeroHandler::shutdown));

            AxonConfiguration rootConfig = testSubject.start();
            rootConfig.shutdown();

            InOrder lifecycleOrder = inOrder(phaseTwoHandler, phaseOneHandlerAdder, phaseOneHandler, phaseZeroHandler);
            lifecycleOrder.verify(phaseTwoHandler).shutdown();
            lifecycleOrder.verify(phaseOneHandlerAdder).addLifecycleHandler(any(), any(), eq(1), any());
            lifecycleOrder.verify(phaseOneHandler).shutdown();
            lifecycleOrder.verify(phaseZeroHandler).shutdown();

            verifyNoInteractions(addedPhaseOneStartHandler);
        }

        @Test
        void failingStartLifecycleProceedsIntoShutdownOrderAtFailingPhase() {
            Assumptions.assumeTrue(
                    doesOwnLifecycleManagement(),
                    "Ignore test since lifecycle management is not managed by the ApplicationConfigurer itself."
            );

            LifecycleManagedInstance phaseZeroHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOneHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseTwoHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseThreeHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseFourHandler = spy(new LifecycleManagedInstance());

            testSubject.lifecycleRegistry(lc -> lc.onStart(0, phaseZeroHandler::start));
            testSubject.lifecycleRegistry(lc -> lc.onStart(1, phaseOneHandler::start));
            // The LifecycleManagedInstance#failingStart() should trigger a shutdown as of phase 2
            testSubject.lifecycleRegistry(lc -> lc.onStart(2, phaseTwoHandler::failingStart));
            testSubject.lifecycleRegistry(lc -> lc.onStart(3, phaseThreeHandler::start));
            testSubject.lifecycleRegistry(lc -> lc.onStart(4, phaseFourHandler::start));

            testSubject.lifecycleRegistry(lc -> lc.onShutdown(4, phaseFourHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(3, phaseThreeHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(2, phaseTwoHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(1, phaseOneHandler::shutdown));
            testSubject.lifecycleRegistry(lc -> lc.onShutdown(0, phaseZeroHandler::shutdown));

            try {
                testSubject.start();
                fail("Expected a LifecycleHandlerInvocationException to be thrown");
            } catch (LifecycleHandlerInvocationException e) {
                assertTrue(e.getCause().getMessage().contains(START_FAILURE_EXCEPTION_MESSAGE));
            }

            InOrder lifecycleOrder = inOrder(phaseZeroHandler,
                                             phaseOneHandler,
                                             phaseTwoHandler,
                                             phaseThreeHandler,
                                             phaseFourHandler);
            lifecycleOrder.verify(phaseZeroHandler).start();
            lifecycleOrder.verify(phaseOneHandler).start();
            lifecycleOrder.verify(phaseTwoHandler).failingStart();
            lifecycleOrder.verify(phaseFourHandler).shutdown();
            lifecycleOrder.verify(phaseThreeHandler).shutdown();
            lifecycleOrder.verify(phaseTwoHandler).shutdown();
            lifecycleOrder.verify(phaseOneHandler).shutdown();
            lifecycleOrder.verify(phaseZeroHandler).shutdown();
        }

        @Test
        void lifecycleHandlersProceedToFollowingPhaseWhenTheThreadIsInterrupted() throws InterruptedException {
            Assumptions.assumeTrue(
                    doesOwnLifecycleManagement(),
                    "Ignore test since lifecycle management is not managed by the ApplicationConfigurer itself."
            );

            AtomicBoolean invoked = new AtomicBoolean(false);

            LifecycleManagedInstance phaseZeroHandler = spy(new LifecycleManagedInstance());
            LifecycleManagedInstance phaseOneHandler = spy(new LifecycleManagedInstance(invoked));
            LifecycleManagedInstance phaseTwoHandler = spy(new LifecycleManagedInstance());

            testSubject.lifecycleRegistry(lc -> lc.onStart(0, phaseZeroHandler::start));
            testSubject.lifecycleRegistry(lc -> lc.onStart(1, phaseOneHandler::uncompletableStart));
            testSubject.lifecycleRegistry(lc -> lc.onStart(2, phaseTwoHandler::start));

            // Start in a different thread to be able to interrupt the thread
            Thread startThread = new Thread(testSubject::start);
            startThread.start();
            startThread.interrupt();

            // Wait until the start thread is finished prior to validating the order.
            startThread.join();

            InOrder lifecycleOrder = inOrder(phaseZeroHandler, phaseOneHandler, phaseTwoHandler);
            lifecycleOrder.verify(phaseZeroHandler).start();
            lifecycleOrder.verify(phaseOneHandler).uncompletableStart();
            lifecycleOrder.verify(phaseTwoHandler).start();
            assertFalse(invoked.get());
        }

        // Suppress Thread.sleep, as it's mandatory for this test.
        @Test
        @SuppressWarnings("java:S2925")
        void timeOutContinuesWithTheNextLifecyclePhase() throws InterruptedException {
            Assumptions.assumeTrue(
                    doesOwnLifecycleManagement(),
                    "Ignore test since lifecycle management is not managed by the ApplicationConfigurer itself."
            );

            CountDownLatch handlerStarted = new CountDownLatch(1);
            AtomicBoolean handler1Invoked = new AtomicBoolean();
            AtomicBoolean handler2Invoked = new AtomicBoolean();
            testSubject.lifecycleRegistry(r -> r
                    .onStart(1, () -> {
                        handlerStarted.countDown();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    })
                    .onStart(1, () -> handler1Invoked.set(true))
                    .onStart(2, () -> handler2Invoked.set(true))
                    .registerLifecyclePhaseTimeout(10, TimeUnit.MILLISECONDS));

            Thread t = new Thread(testSubject::start);
            t.start();
            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS), "Handler didn't start in a second.");

            assertWithin(1, TimeUnit.SECONDS, () -> assertTrue(handler2Invoked.get()));
            assertWithin(1, TimeUnit.SECONDS, () -> assertTrue(handler1Invoked.get()));

            t.join();
        }

        @Test
        void factoryRegisteredShutdownHandlersAreInvoked() {
            Assumptions.assumeTrue(
                    doesOwnLifecycleManagement(),
                    "Ignore test since lifecycle management is not managed by the ApplicationConfigurer itself."
            );

            // given...
            AtomicBoolean shutdownInvoked = new AtomicBoolean(false);
            testSubject.componentRegistry(registry -> registry.registerFactory(new TestComponentFactory(
                    "constructed", new AtomicBoolean(true), shutdownInvoked
            )));
            AxonConfiguration config = testSubject.start();
            // when...
            config.shutdown();
            // then...
            await("Await until factory shutdown handler was invoked").pollDelay(Duration.ofMillis(50))
                                                                     .atMost(Duration.ofSeconds(5))
                                                                     .until(shutdownInvoked::get);
        }

        @FunctionalInterface
        protected interface LifecycleRegistration {

            void registerLifecycleHandler(LifecycleRegistry lifecycleRegistry, int phase, Runnable lifecycleHandler);
        }

        static class LifecycleManagedInstance {

            private final ReentrantLock lock;
            private final AtomicBoolean invoked;

            protected LifecycleManagedInstance() {
                this(new ReentrantLock(), new AtomicBoolean(false));
            }

            protected LifecycleManagedInstance(ReentrantLock lock) {
                this(lock, new AtomicBoolean(false));
            }

            protected LifecycleManagedInstance(AtomicBoolean invoked) {
                this(new ReentrantLock(), invoked);
            }

            protected LifecycleManagedInstance(ReentrantLock lock, AtomicBoolean invoked) {
                this.lock = lock;
                this.invoked = invoked;
            }

            public void start() {
                // No-op
            }

            protected CompletableFuture<Void> slowStart() {
                return CompletableFuture.runAsync(() -> {
                    try {
                        LoggerFactory.getLogger(ApplicationConfigurerTestSuite.class)
                                     .info("Attempting to acquire lock");
                        lock.lock();
                    } finally {
                        LoggerFactory.getLogger(ApplicationConfigurerTestSuite.class).info("Lock acquired, unlocking");
                        lock.unlock();
                    }
                });
            }

            protected CompletableFuture<Object> uncompletableStart() {
                return new CompletableFuture<>().whenComplete((r, e) -> invoked.set(true));
            }

            protected void addLifecycleHandler(LifecycleRegistration lifecycleRegistration,
                                               LifecycleRegistry lifecycleRegistry, int phase,
                                               Runnable lifecycleHandler) {
                lifecycleRegistration.registerLifecycleHandler(lifecycleRegistry, phase, lifecycleHandler);
            }

            protected void shutdown() {
                // No-op
            }

            protected CompletableFuture<Void> slowShutdown() {
                return CompletableFuture.runAsync(() -> {
                    try {
                        lock.lock();
                    } finally {
                        lock.unlock();
                    }
                });
            }

            protected void failingStart() {
                throw new RuntimeException(START_FAILURE_EXCEPTION_MESSAGE);
            }
        }
    }
}