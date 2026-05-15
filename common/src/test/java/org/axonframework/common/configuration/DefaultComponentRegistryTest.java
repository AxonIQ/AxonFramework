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

import org.axonframework.common.TypeReference;
import org.axonframework.common.infra.ComponentDescriptor;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link DefaultComponentRegistry}.
 *
 * @author Allard Buijze
 */
class DefaultComponentRegistryTest {

    private static final String INIT_STATE = "initial-state";
    private static final TestComponent TEST_COMPONENT = new TestComponent(INIT_STATE);

    private DefaultComponentRegistry testSubject;
    private LifecycleRegistry lifecycleRegistry;

    @BeforeEach
    void setUp() {
        testSubject = new DefaultComponentRegistry();
        lifecycleRegistry = mock(LifecycleRegistry.class);
    }

    @Test
    void registerComponentExposesRegisteredComponentUponBuild() {
        TestComponent testComponent = TEST_COMPONENT;
        testSubject.registerComponent(TestComponent.class, c -> testComponent);

        Configuration config = testSubject.build(mock());

        assertEquals(testComponent, config.getComponent(TestComponent.class));
    }

    @Test
    void registerComponentExposesRegisteredComponentOnOptionalGet() {
        TestComponent testComponent = TEST_COMPONENT;
        testSubject.registerComponent(TestComponent.class, c -> testComponent);

        Configuration config = testSubject.build(mock());

        Optional<TestComponent> result = config.getOptionalComponent(TestComponent.class);

        assertTrue(result.isPresent());
        assertEquals(testComponent, result.get());
    }

    @Test
    void getOptionalComponentResultsInEmptyOptionalForUnregisteredComponent() {
        Optional<TestComponent> result = testSubject.build(mock()).getOptionalComponent(TestComponent.class);

        assertFalse(result.isPresent());
    }

    @Test
    void canRegisterMultipleComponentsOfTheSameTypeForDifferentNames() {
        String testNameOne = "one";
        String testNameTwo = "two";
        TestComponent testComponentOne = new TestComponent(testNameOne);
        TestComponent testComponentTwo = new TestComponent(testNameTwo);
        testSubject.registerComponent(TestComponent.class, testNameOne, c -> testComponentOne)
                   .registerComponent(TestComponent.class, testNameTwo, c -> testComponentTwo);

        Configuration config = testSubject.build(mock());

        assertEquals(testComponentOne, config.getComponent(TestComponent.class, testNameOne));
        assertEquals(testComponentTwo, config.getComponent(TestComponent.class, testNameTwo));
    }

    @Test
    void componentBuilderIsInvokedOnceUponRetrievalOfComponent() {
        AtomicInteger invocationCounter = new AtomicInteger(0);
        testSubject.registerComponent(TestComponent.class, "name", c -> {
            invocationCounter.incrementAndGet();
            return TEST_COMPONENT;
        });

        Configuration config = testSubject.build(mock());

        assertEquals(0, invocationCounter.get());
        config.getComponent(TestComponent.class, "name");
        assertEquals(1, invocationCounter.get());
        config.getComponent(TestComponent.class, "name");
        assertEquals(1, invocationCounter.get());
    }

    @Test
    void registeringComponentsForTheSameTypeReplacesThePreviousComponentBuilder() {
        TestComponent testComponent = new TestComponent("replaced-component");
        TestComponent expectedComponent = new TestComponent("the-winner");
        testSubject.registerComponent(TestComponent.class, c -> testComponent)
                   .registerComponent(TestComponent.class, c -> expectedComponent);

        Configuration config = testSubject.build(mock());

        assertNotEquals(testComponent, config.getComponent(TestComponent.class));
        assertEquals(expectedComponent, config.getComponent(TestComponent.class));
    }

    @Test
    void registeringComponentsForTheSameTypeAndNameReplacesThePreviousComponentBuilder() {
        TestComponent testComponent = new TestComponent("replaced-component");
        TestComponent expectedComponent = new TestComponent("the-winner");
        testSubject.registerComponent(TestComponent.class, "name", c -> testComponent)
                   .registerComponent(TestComponent.class, "name", c -> expectedComponent);

        Configuration config = testSubject.build(mock());

        assertNotEquals(testComponent, config.getComponent(TestComponent.class, "name"));
        assertEquals(expectedComponent, config.getComponent(TestComponent.class, "name"));
    }

    @Test
    void getComponentWithDefaultInvokesSupplierWhenThereIsNoRegisteredComponentForTheGivenClass() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        TestComponent defaultComponent = new TestComponent("default");
        TestComponent registeredComponent = TEST_COMPONENT;
        testSubject.registerComponent(TestComponent.class, "id", c -> registeredComponent);

        Configuration config = testSubject.build(mock());

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
    void nestedConfigurationCanAccessComponentsFromParent() {
        testSubject.registerModule(
                           new TestModule("test1").componentRegistry(
                                   cr -> cr.registerComponent(
                                           String.class, "child",
                                           c -> c.getComponent(String.class, "parent") + "&child"
                                   )
                           )
                   )
                   .registerComponent(String.class, "parent", c -> "parent");
        Configuration actual = testSubject.build(mock());

        assertEquals("parent", actual.getComponent(String.class, "parent"));
        assertThrows(ComponentNotFoundException.class, () -> actual.getComponent(String.class, "child"));

        assertEquals("parent&child", actual.getModuleConfigurations().getFirst().getComponent(String.class, "child"));
    }

    @Test
    @Timeout(5)
    void moduleComponentUsingGetComponentWithDefaultFromParentDoesNotCauseStackOverflow() {
        // given
        testSubject.disableEnhancerScanning();
        testSubject.registerComponent(String.class, c -> "root-value");
        testSubject.registerModule(
                new TestModule("test-module").componentRegistry(
                        cr -> cr.registerComponent(
                                TestComponent.class, "child",
                                c -> new TestComponent(c.getComponent(String.class, () -> "fallback"))
                        )
                )
        );

        // when
        Configuration config = testSubject.build(new StubLifecycleRegistry());

        // then - should resolve "root-value" from parent without StackOverflowError
        TestComponent result = config.getModuleConfigurations().getFirst()
                                     .getComponent(TestComponent.class, "child");
        assertEquals("root-value", result.state());
    }

    @Test
    void enhancersAreInvokedInOrder() {
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

        testSubject.registerEnhancer(enhancerWithDefaultOrder)
                   .registerEnhancer(enhancerWithLowOrder)
                   .registerEnhancer(enhancerWithHighOrder);

        testSubject.build(mock());

        InOrder enhancementOrder = inOrder(enhancerWithLowOrder, enhancerWithDefaultOrder, enhancerWithHighOrder);
        enhancementOrder.verify(enhancerWithLowOrder).enhance(any());
        enhancementOrder.verify(enhancerWithDefaultOrder).enhance(any());
        enhancementOrder.verify(enhancerWithHighOrder).enhance(any());
    }

    @Test
    void buildWillScanAndCallEnhancersFromClasspathIfNotDisabled() {
        TestConfigurationEnhancer.withActiveTestEnhancer(() -> {
            testSubject.build(new StubLifecycleRegistry());
            assertTrue(TestConfigurationEnhancer.hasEnhanced());
        });
    }

    @Test
    void disableEnhancerScanningWillDisableAllEnhancers() {
        testSubject.disableEnhancerScanning();

        TestConfigurationEnhancer.withActiveTestEnhancer(() -> {
            testSubject.build(new StubLifecycleRegistry());
            assertFalse(TestConfigurationEnhancer.hasEnhanced());
        });
    }


    @Test
    void disableSpecificEnhancerWillDisableLoadingOfThatEnhancer() {
        testSubject.disableEnhancer(TestConfigurationEnhancer.class);

        TestConfigurationEnhancer.withActiveTestEnhancer(() -> {
            testSubject.build(new StubLifecycleRegistry());
            assertFalse(TestConfigurationEnhancer.hasEnhanced());
        });
    }

    @Test
    void disableSpecificEnhancerByClassNameWillDisableLoadingOfThatEnhancer() {
        var className = TestConfigurationEnhancer.class.getName();
        testSubject.disableEnhancer(className);

        TestConfigurationEnhancer.withActiveTestEnhancer(() -> {
            testSubject.build(new StubLifecycleRegistry());
            assertFalse(TestConfigurationEnhancer.hasEnhanced());
        });
    }

    @Nested
    class ComponentRegistrationAndRetrieval {

        @Nested
        class InterfaceVsImplementationRegistration {

            @Test
            void registerByInterfaceRetrieveByInterface() {
                // given
                ServiceImplA implementation = new ServiceImplA("test-value");
                testSubject.registerComponent(ServiceInterface.class, c -> implementation);

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertTrue(config.hasComponent(ServiceInterface.class));
                ServiceInterface result = config.getComponent(ServiceInterface.class);
                assertEquals("test-value", result.getValue());
                assertSame(implementation, result);
            }

            @Test
            void registerByInterfaceRetrieveByImplementationFails() {
                // given
                ServiceImplA implementation = new ServiceImplA("test-value");
                testSubject.registerComponent(ServiceInterface.class, c -> implementation);

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertFalse(config.hasComponent(ServiceImplA.class));
                assertThrows(ComponentNotFoundException.class, () -> config.getComponent(ServiceImplA.class));
            }

            @Test
            void registerByImplementationIsAlsoRetrievableByInterface() {
                // given
                ServiceImplA implementation = new ServiceImplA("test-value");
                testSubject.registerComponent(ServiceImplA.class, c -> implementation);

                // when
                Configuration config = testSubject.build(mock());

                // then
                // Component registered by implementation class is accessible by implementation
                assertTrue(config.hasComponent(ServiceImplA.class));
                ServiceImplA resultByImpl = config.getComponent(ServiceImplA.class);
                assertEquals("test-value", resultByImpl.getValue());
                assertSame(implementation, resultByImpl);

                // Framework also makes it available by interface automatically
                assertTrue(config.hasComponent(ServiceInterface.class));
                ServiceInterface resultByInterface = config.getComponent(ServiceInterface.class);
                assertEquals("test-value", resultByInterface.getValue());
                assertSame(implementation, resultByInterface);
            }

            @Test
            void registerByImplementationRetrieveByImplementation() {
                // given
                ServiceImplA implementation = new ServiceImplA("test-value");
                testSubject.registerComponent(ServiceImplA.class, c -> implementation);

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertTrue(config.hasComponent(ServiceImplA.class));
                ServiceImplA result = config.getComponent(ServiceImplA.class);
                assertEquals("test-value", result.getValue());
                assertSame(implementation, result);
            }

            @Test
            void registerByInterfaceWithDifferentImplementationsReplacesComponent() {
                // given
                ServiceImplA implA = new ServiceImplA("value-a");
                ServiceImplB implB = new ServiceImplB("value-b");

                testSubject.registerComponent(ServiceInterface.class, c -> implA)
                           .registerComponent(ServiceInterface.class, c -> implB);

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertTrue(config.hasComponent(ServiceInterface.class));
                ServiceInterface result = config.getComponent(ServiceInterface.class);
                assertEquals("value-b", result.getValue());
                assertSame(implB, result);
                assertNotSame(implA, result);
            }
        }

        @Nested
        class MultipleImplementationsWithoutNames {

            @Test
            void registeringMultipleImplementationsWithoutNamesReplacesComponent() {
                // given
                ServiceImplA implA = new ServiceImplA("value-a");
                ServiceImplB implB = new ServiceImplB("value-b");

                testSubject.registerComponent(ServiceInterface.class, c -> implA)
                           .registerComponent(ServiceInterface.class, c -> implB);

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertTrue(config.hasComponent(ServiceInterface.class));
                ServiceInterface result = config.getComponent(ServiceInterface.class);
                assertEquals("value-b", result.getValue());
                assertSame(implB, result);
            }

            @Test
            void hasComponentReturnsTrueOnlyForLastRegisteredComponent() {
                // given
                ServiceImplA implA = new ServiceImplA("value-a");
                ServiceImplB implB = new ServiceImplB("value-b");

                testSubject.registerComponent(ServiceInterface.class, c -> implA)
                           .registerComponent(ServiceInterface.class, c -> implB);

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertTrue(config.hasComponent(ServiceInterface.class));
                // Cannot check by implementation type as registration is by interface
                assertFalse(config.hasComponent(ServiceImplA.class));
                assertFalse(config.hasComponent(ServiceImplB.class));
            }

            @Test
            void getOptionalComponentReturnsOnlyLastRegisteredComponent() {
                // given
                ServiceImplA implA = new ServiceImplA("value-a");
                ServiceImplB implB = new ServiceImplB("value-b");

                testSubject.registerComponent(ServiceInterface.class, c -> implA)
                           .registerComponent(ServiceInterface.class, c -> implB);

                // when
                Configuration config = testSubject.build(mock());

                // then
                Optional<ServiceInterface> result = config.getOptionalComponent(ServiceInterface.class);
                assertTrue(result.isPresent());
                assertEquals("value-b", result.get().getValue());
                assertSame(implB, result.get());
            }

            @Test
            void componentBuilderIsOnlyInvokedForLastRegisteredComponent() {
                // given
                AtomicInteger counterA = new AtomicInteger(0);
                AtomicInteger counterB = new AtomicInteger(0);

                testSubject.registerComponent(ServiceInterface.class, c -> {
                               counterA.incrementAndGet();
                               return new ServiceImplA("value-a");
                           })
                           .registerComponent(ServiceInterface.class, c -> {
                               counterB.incrementAndGet();
                               return new ServiceImplB("value-b");
                           });

                // when
                Configuration config = testSubject.build(mock());
                config.getComponent(ServiceInterface.class);

                // then
                assertEquals(0, counterA.get(), "First builder should not be invoked");
                assertEquals(1, counterB.get(), "Second builder should be invoked once");
            }
        }

        @Nested
        class MultipleImplementationsWithNames {

            @Test
            void registerMultipleImplementationsWithDifferentNamesAllAccessible() {
                // given
                ServiceImplA implA = new ServiceImplA("value-a");
                ServiceImplB implB = new ServiceImplB("value-b");

                testSubject.registerComponent(ServiceInterface.class, "service-a", c -> implA)
                           .registerComponent(ServiceInterface.class, "service-b", c -> implB);

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertTrue(config.hasComponent(ServiceInterface.class, "service-a"));
                assertTrue(config.hasComponent(ServiceInterface.class, "service-b"));

                ServiceInterface resultA = config.getComponent(ServiceInterface.class, "service-a");
                assertEquals("value-a", resultA.getValue());
                assertSame(implA, resultA);

                ServiceInterface resultB = config.getComponent(ServiceInterface.class, "service-b");
                assertEquals("value-b", resultB.getValue());
                assertSame(implB, resultB);
            }

            @Test
            void registerWithSameNameReplacesComponent() {
                // given
                ServiceImplA implA = new ServiceImplA("value-a");
                ServiceImplB implB = new ServiceImplB("value-b");

                testSubject.registerComponent(ServiceInterface.class, "service", c -> implA)
                           .registerComponent(ServiceInterface.class, "service", c -> implB);

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertTrue(config.hasComponent(ServiceInterface.class, "service"));
                ServiceInterface result = config.getComponent(ServiceInterface.class, "service");
                assertEquals("value-b", result.getValue());
                assertSame(implB, result);
            }

            @Test
            void namedComponentIsNotAccessibleWithoutName() {
                // given
                ServiceImplA implA = new ServiceImplA("value-a");
                testSubject.registerComponent(ServiceInterface.class, "service-a", c -> implA);

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertTrue(config.hasComponent(ServiceInterface.class, "service-a"));
                assertFalse(config.hasComponent(ServiceInterface.class),
                            "Named component should not be accessible without name");
                assertThrows(ComponentNotFoundException.class,
                             () -> config.getComponent(ServiceInterface.class),
                             "Should throw when retrieving named component without name");
            }

            @Test
            void unnamedComponentIsNotAccessibleByArbitraryName() {
                // given
                ServiceImplA implA = new ServiceImplA("value-a");
                testSubject.registerComponent(ServiceInterface.class, c -> implA);

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertTrue(config.hasComponent(ServiceInterface.class));
                assertFalse(config.hasComponent(ServiceInterface.class, "some-name"));
                assertThrows(ComponentNotFoundException.class,
                             () -> config.getComponent(ServiceInterface.class, "some-name"),
                             "Should throw when retrieving unnamed component with a name");
            }

            @Test
            void canRegisterBothNamedAndUnnamedComponentsOfSameType() {
                // given
                ServiceImplA unnamedImpl = new ServiceImplA("unnamed");
                ServiceImplA namedImpl = new ServiceImplA("named");

                testSubject.registerComponent(ServiceInterface.class, c -> unnamedImpl)
                           .registerComponent(ServiceInterface.class, "named-service", c -> namedImpl);

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertTrue(config.hasComponent(ServiceInterface.class));
                assertTrue(config.hasComponent(ServiceInterface.class, "named-service"));

                ServiceInterface unnamedResult = config.getComponent(ServiceInterface.class);
                assertEquals("unnamed", unnamedResult.getValue());
                assertSame(unnamedImpl, unnamedResult);

                ServiceInterface namedResult = config.getComponent(ServiceInterface.class, "named-service");
                assertEquals("named", namedResult.getValue());
                assertSame(namedImpl, namedResult);
            }

            @Test
            void getOptionalComponentWithNameReturnsCorrectComponent() {
                // given
                ServiceImplA implA = new ServiceImplA("value-a");
                ServiceImplB implB = new ServiceImplB("value-b");

                testSubject.registerComponent(ServiceInterface.class, "service-a", c -> implA)
                           .registerComponent(ServiceInterface.class, "service-b", c -> implB);

                // when
                Configuration config = testSubject.build(mock());

                // then
                Optional<ServiceInterface> resultA = config.getOptionalComponent(ServiceInterface.class, "service-a");
                assertTrue(resultA.isPresent());
                assertEquals("value-a", resultA.get().getValue());

                Optional<ServiceInterface> resultB = config.getOptionalComponent(ServiceInterface.class, "service-b");
                assertTrue(resultB.isPresent());
                assertEquals("value-b", resultB.get().getValue());

                Optional<ServiceInterface> nonExistent = config.getOptionalComponent(ServiceInterface.class,
                                                                                     "non-existent");
                assertFalse(nonExistent.isPresent());
            }
        }

        @Nested
        class ComponentNotFoundScenarios {

            @Test
            void retrievingUnregisteredComponentThrowsComponentNotFoundException() {
                // given
                Configuration config = testSubject.build(mock());

                // when / then
                assertThrows(ComponentNotFoundException.class,
                             () -> config.getComponent(ServiceInterface.class));
            }

            @Test
            void retrievingUnregisteredComponentByNameThrowsComponentNotFoundException() {
                // given
                Configuration config = testSubject.build(mock());

                // when / then
                assertThrows(ComponentNotFoundException.class,
                             () -> config.getComponent(ServiceInterface.class, "non-existent"));
            }

            @Test
            void retrievingByWrongTypeThrowsComponentNotFoundException() {
                // given
                testSubject.registerComponent(ServiceInterface.class, c -> new ServiceImplA("test"));

                // when
                Configuration config = testSubject.build(mock());

                // then
                assertThrows(ComponentNotFoundException.class,
                             () -> config.getComponent(String.class),
                             "Should throw when requesting component by wrong type");
            }

            @Test
            void getOptionalComponentReturnsEmptyForNonExistentComponent() {
                // given
                Configuration config = testSubject.build(mock());

                // when
                Optional<ServiceInterface> result = config.getOptionalComponent(ServiceInterface.class);

                // then
                assertFalse(result.isPresent());
            }

            @Test
            void getOptionalComponentReturnsEmptyForNonExistentNamedComponent() {
                // given
                testSubject.registerComponent(ServiceInterface.class, c -> new ServiceImplA("test"));
                Configuration config = testSubject.build(mock());

                // when
                Optional<ServiceInterface> result = config.getOptionalComponent(ServiceInterface.class, "non-existent");

                // then
                assertFalse(result.isPresent());
            }

            @Test
            void hasComponentReturnsFalseForUnregisteredComponent() {
                // given
                Configuration config = testSubject.build(mock());

                // when / then
                assertFalse(config.hasComponent(ServiceInterface.class));
            }

            @Test
            void hasComponentReturnsFalseForUnregisteredNamedComponent() {
                // given
                testSubject.registerComponent(ServiceInterface.class, c -> new ServiceImplA("test"));
                Configuration config = testSubject.build(mock());

                // when / then
                assertFalse(config.hasComponent(ServiceInterface.class, "non-existent"));
            }

            @Test
            void getComponentWithDefaultSupplierReturnsDefaultForNonExistentComponent() {
                // given
                Configuration config = testSubject.build(mock());
                ServiceImplA defaultImpl = new ServiceImplA("default");

                // when
                ServiceInterface result = config.getComponent(ServiceInterface.class, () -> defaultImpl);

                // then
                assertSame(defaultImpl, result);
                assertEquals("default", result.getValue());
            }

            @Test
            void getComponentWithDefaultSupplierReturnsRegisteredComponentWhenExists() {
                // given
                ServiceImplA registeredImpl = new ServiceImplA("registered");
                ServiceImplA defaultImpl = new ServiceImplA("default");
                testSubject.registerComponent(ServiceInterface.class, c -> registeredImpl);

                // when
                Configuration config = testSubject.build(mock());
                ServiceInterface result = config.getComponent(ServiceInterface.class, () -> defaultImpl);

                // then
                assertSame(registeredImpl, result);
                assertEquals("registered", result.getValue());
                assertNotSame(defaultImpl, result);
            }
        }

        @Nested
        class RegisterIfNotPresentBehavior {

            @Nested
            class WithoutNames {

                @Test
                void registerIfNotPresentWithInterfaceWhenNothingRegistered() {
                    // given
                    ServiceImplA implementation = new ServiceImplA("test-value");
                    testSubject.registerIfNotPresent(ServiceInterface.class, c -> implementation);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    assertTrue(config.hasComponent(ServiceInterface.class));
                    ServiceInterface result = config.getComponent(ServiceInterface.class);
                    assertEquals("test-value", result.getValue());
                    assertSame(implementation, result);
                }

                @Test
                void registerIfNotPresentWithInterfaceWhenInterfaceAlreadyRegistered() {
                    // given
                    ServiceImplA existingImpl = new ServiceImplA("existing");
                    ServiceImplB newImpl = new ServiceImplB("new");

                    testSubject.registerComponent(ServiceInterface.class, c -> existingImpl)
                               .registerIfNotPresent(ServiceInterface.class, c -> newImpl);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    ServiceInterface result = config.getComponent(ServiceInterface.class);
                    assertEquals("existing", result.getValue());
                    assertSame(existingImpl, result);
                    assertNotSame(newImpl, result);
                }

                @Test
                void registerIfNotPresentWithInterfaceWhenImplementationAlreadyRegistered() {
                    // given
                    ServiceImplA existingImpl = new ServiceImplA("existing");
                    ServiceImplB newImpl = new ServiceImplB("new");

                    // Register by implementation class - makes it available by interface too
                    testSubject.registerComponent(ServiceImplA.class, c -> existingImpl)
                               .registerIfNotPresent(ServiceInterface.class, c -> newImpl);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    // Should use existing implementation since it's available by interface
                    assertTrue(config.hasComponent(ServiceInterface.class));
                    ServiceInterface result = config.getComponent(ServiceInterface.class);
                    assertEquals("existing", result.getValue());
                    assertSame(existingImpl, result);
                    assertNotSame(newImpl, result);
                }

                @Test
                void registerIfNotPresentWithImplementationWhenNothingRegistered() {
                    // given
                    ServiceImplA implementation = new ServiceImplA("test-value");
                    testSubject.registerIfNotPresent(ServiceImplA.class, c -> implementation);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    assertTrue(config.hasComponent(ServiceImplA.class));
                    ServiceImplA result = config.getComponent(ServiceImplA.class);
                    assertEquals("test-value", result.getValue());
                    assertSame(implementation, result);

                    // Also available by interface
                    assertTrue(config.hasComponent(ServiceInterface.class));
                }

                @Test
                void registerIfNotPresentWithImplementationWhenInterfaceAlreadyRegistered() {
                    // given
                    ServiceImplA existingImpl = new ServiceImplA("existing");
                    ServiceImplB newImpl = new ServiceImplB("new");

                    testSubject.registerComponent(ServiceInterface.class, c -> existingImpl)
                               .registerIfNotPresent(ServiceImplB.class, c -> newImpl);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    // Interface is registered, but not the specific implementation type
                    // So registerIfNotPresent should register the new implementation
                    assertTrue(config.hasComponent(ServiceImplB.class));
                    ServiceImplB result = config.getComponent(ServiceImplB.class);
                    assertEquals("new", result.getValue());
                    assertSame(newImpl, result);

                    // Original interface registration still works
                    ServiceInterface interfaceResult = config.getComponent(ServiceInterface.class);
                    assertSame(existingImpl, interfaceResult);
                }

                @Test
                void registerIfNotPresentWithImplementationWhenImplementationAlreadyRegistered() {
                    // given
                    ServiceImplA existingImpl = new ServiceImplA("existing");
                    ServiceImplA newImpl = new ServiceImplA("new");

                    testSubject.registerComponent(ServiceImplA.class, c -> existingImpl)
                               .registerIfNotPresent(ServiceImplA.class, c -> newImpl);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    ServiceImplA result = config.getComponent(ServiceImplA.class);
                    assertEquals("existing", result.getValue());
                    assertSame(existingImpl, result);
                    assertNotSame(newImpl, result);
                }

                @Test
                void ambiguousComponentMatchWhenMultipleImplementationsRegisteredByImplClass() {
                    // given
                    ServiceImplA implA = new ServiceImplA("a");
                    ServiceImplB implB = new ServiceImplB("b");

                    // Register BOTH by their implementation classes
                    testSubject.registerComponent(ServiceImplA.class, c -> implA)
                               .registerComponent(ServiceImplB.class, c -> implB);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    // Both implementations can be retrieved by their specific types
                    assertEquals("a", config.getComponent(ServiceImplA.class).getValue());
                    assertEquals("b", config.getComponent(ServiceImplB.class).getValue());

                    // BUT trying to retrieve by interface throws AmbiguousComponentMatchException
                    // because there's no exact match for ServiceInterface, so it searches for assignable types
                    // and finds BOTH ServiceImplA and ServiceImplB
                    assertThrows(AmbiguousComponentMatchException.class,
                                 () -> config.getComponent(ServiceInterface.class),
                                 "Should throw ambiguity exception when multiple implementations registered by impl class");
                }

                @Test
                void componentLookupAlgorithmExactMatchVsAssignability() {
                    // given
                    ServiceImplA implA = new ServiceImplA("registered-by-interface");
                    ServiceImplB implB = new ServiceImplB("registered-by-impl");

                    testSubject.registerComponent(ServiceInterface.class,
                                                  c -> implA)  // Exact match for ServiceInterface
                               .registerComponent(ServiceImplB.class, c -> implB);      // Registered by implementation

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    // SCENARIO 1: Exact match exists → Uses exact match, no assignability check
                    ServiceInterface byInterface = config.getComponent(ServiceInterface.class);
                    assertEquals("registered-by-interface", byInterface.getValue());
                    assertSame(implA, byInterface);
                    // Even though ServiceImplB also implements ServiceInterface,
                    // it doesn't cause ambiguity because exact match wins

                    // SCENARIO 2: Exact match exists for implementation class
                    ServiceImplB byImpl = config.getComponent(ServiceImplB.class);
                    assertEquals("registered-by-impl", byImpl.getValue());
                    assertSame(implB, byImpl);

                    // SCENARIO 3: Assignability check only works "upward" (interface ← impl), NOT "downward" (impl ← interface)
                    // ServiceImplA was registered by ServiceInterface.class, not ServiceImplA.class
                    // Asking for ServiceImplA.class has no exact match
                    // Assignability check: ServiceImplA.isAssignableFrom(ServiceInterface) = FALSE
                    // Therefore, component NOT found
                    assertThrows(ComponentNotFoundException.class,
                                 () -> config.getComponent(ServiceImplA.class),
                                 "Cannot retrieve by implementation when registered by interface");

                    // But we CAN get it by the registered type (interface)
                    assertNotNull(config.getComponent(ServiceInterface.class));
                }

                @Test
                void assignabilityCheckFindsImplementationWhenNoExactMatch() {
                    // given
                    ServiceImplA implA = new ServiceImplA("only-by-impl");

                    // Register ONLY by implementation class, NOT by interface
                    testSubject.registerComponent(ServiceImplA.class, c -> implA);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    // EXACT MATCH: ServiceImplA.class → works
                    ServiceImplA byImpl = config.getComponent(ServiceImplA.class);
                    assertEquals("only-by-impl", byImpl.getValue());

                    // NO EXACT MATCH: ServiceInterface.class
                    // → Searches for components where ServiceInterface.isAssignableFrom(component.type)
                    // → Finds ServiceImplA because ServiceInterface.isAssignableFrom(ServiceImplA) = true
                    // → Returns the component
                    ServiceInterface byInterface = config.getComponent(ServiceInterface.class);
                    assertEquals("only-by-impl", byInterface.getValue());
                    assertSame(implA, byInterface);
                }

                @Test
                void hasComponentReturnsTrueForExactMatchAndAssignableTypes() {
                    // given
                    ServiceImplA implA = new ServiceImplA("impl");
                    testSubject.registerComponent(ServiceImplA.class, c -> implA);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    // hasComponent checks BOTH exact match AND assignability
                    assertTrue(config.hasComponent(ServiceImplA.class),
                               "Should find exact match");
                    assertTrue(config.hasComponent(ServiceInterface.class),
                               "Should find via assignability check");
                }
            }

            @Nested
            class WithNames {

                @Test
                void registerIfNotPresentWithNameWhenNothingRegistered() {
                    // given
                    ServiceImplA implementation = new ServiceImplA("test-value");
                    testSubject.registerIfNotPresent(ServiceInterface.class, "named-service", c -> implementation);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    assertTrue(config.hasComponent(ServiceInterface.class, "named-service"));
                    ServiceInterface result = config.getComponent(ServiceInterface.class, "named-service");
                    assertEquals("test-value", result.getValue());
                    assertSame(implementation, result);
                }

                @Test
                void registerIfNotPresentWithNameWhenSameNameExists() {
                    // given
                    ServiceImplA existingImpl = new ServiceImplA("existing");
                    ServiceImplB newImpl = new ServiceImplB("new");

                    testSubject.registerComponent(ServiceInterface.class, "named-service", c -> existingImpl)
                               .registerIfNotPresent(ServiceInterface.class, "named-service", c -> newImpl);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    ServiceInterface result = config.getComponent(ServiceInterface.class, "named-service");
                    assertEquals("existing", result.getValue());
                    assertSame(existingImpl, result);
                    assertNotSame(newImpl, result);
                }

                @Test
                void registerIfNotPresentWithNameWhenDifferentNameExists() {
                    // given
                    ServiceImplA existingImpl = new ServiceImplA("existing");
                    ServiceImplB newImpl = new ServiceImplB("new");

                    testSubject.registerComponent(ServiceInterface.class, "service-one", c -> existingImpl)
                               .registerIfNotPresent(ServiceInterface.class, "service-two", c -> newImpl);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    // Both should be registered as they have different names
                    assertTrue(config.hasComponent(ServiceInterface.class, "service-one"));
                    assertTrue(config.hasComponent(ServiceInterface.class, "service-two"));

                    ServiceInterface resultOne = config.getComponent(ServiceInterface.class, "service-one");
                    assertEquals("existing", resultOne.getValue());

                    ServiceInterface resultTwo = config.getComponent(ServiceInterface.class, "service-two");
                    assertEquals("new", resultTwo.getValue());
                }

                @Test
                void registerIfNotPresentNamedWhenUnnamedExists() {
                    // given
                    ServiceImplA unnamedImpl = new ServiceImplA("unnamed");
                    ServiceImplB namedImpl = new ServiceImplB("named");

                    testSubject.registerComponent(ServiceInterface.class, c -> unnamedImpl)
                               .registerIfNotPresent(ServiceInterface.class, "named-service", c -> namedImpl);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    // Both should exist - unnamed and named are separate
                    assertTrue(config.hasComponent(ServiceInterface.class));
                    assertTrue(config.hasComponent(ServiceInterface.class, "named-service"));

                    ServiceInterface unnamedResult = config.getComponent(ServiceInterface.class);
                    assertEquals("unnamed", unnamedResult.getValue());

                    ServiceInterface namedResult = config.getComponent(ServiceInterface.class, "named-service");
                    assertEquals("named", namedResult.getValue());
                }

                @Test
                void registerIfNotPresentUnnamedWhenNamedExists() {
                    // given
                    ServiceImplA namedImpl = new ServiceImplA("named");
                    ServiceImplB unnamedImpl = new ServiceImplB("unnamed");

                    testSubject.registerComponent(ServiceInterface.class, "named-service", c -> namedImpl)
                               .registerIfNotPresent(ServiceInterface.class, c -> unnamedImpl);

                    // when
                    Configuration config = testSubject.build(mock());

                    // then
                    // registerIfNotPresent checks hasComponent during configuration time
                    // At configuration time, ComponentRegistry.hasComponent checks if ANY component of the type exists
                    // So when a named component exists, hasComponent(type) returns true
                    // Therefore registerIfNotPresent skips registration
                    assertFalse(config.hasComponent(ServiceInterface.class),
                                "Unnamed component not registered because named one already exists");
                    assertTrue(config.hasComponent(ServiceInterface.class, "named-service"));

                    ServiceInterface namedResult = config.getComponent(ServiceInterface.class, "named-service");
                    assertEquals("named", namedResult.getValue());
                }
            }

            @Nested
            class SearchScopeBehavior {

                @Test
                void registerIfNotPresentWithSearchScopeCurrentOnlyChecksCurrentRegistry() {
                    // given
                    ServiceImplA parentImpl = new ServiceImplA("parent");
                    ServiceImplB childImpl = new ServiceImplB("child");

                    // Register in parent
                    testSubject.registerComponent(ServiceInterface.class, c -> parentImpl);

                    // Register module with child registry
                    testSubject.registerModule(
                            new TestModule("test-module").componentRegistry(
                                    childRegistry -> childRegistry.registerIfNotPresent(
                                            ServiceInterface.class,
                                            c -> childImpl,
                                            SearchScope.CURRENT
                                    )
                            )
                    );

                    // when
                    Configuration config = testSubject.build(mock());
                    Configuration moduleConfig = config.getModuleConfiguration("test-module").orElseThrow();

                    // then
                    // Parent has its component
                    ServiceInterface parentResult = config.getComponent(ServiceInterface.class);
                    assertEquals("parent", parentResult.getValue());

                    // Child registered its own component since CURRENT scope doesn't check parent
                    ServiceInterface childResult = moduleConfig.getComponent(ServiceInterface.class);
                    assertEquals("child", childResult.getValue());
                }

                @Test
                void registerIfNotPresentWithSearchScopeAllStillRegistersInChildModule() {
                    // given
                    ServiceImplA parentImpl = new ServiceImplA("parent");
                    ServiceImplB childImpl = new ServiceImplB("child");

                    // Register in parent
                    testSubject.registerComponent(ServiceInterface.class, c -> parentImpl);

                    // Register module with child registry
                    testSubject.registerModule(
                            new TestModule("test-module").componentRegistry(
                                    childRegistry -> childRegistry.registerIfNotPresent(
                                            ServiceInterface.class,
                                            c -> childImpl,
                                            SearchScope.ALL
                                    )
                            )
                    );

                    // when
                    Configuration config = testSubject.build(mock());
                    Configuration moduleConfig = config.getModuleConfiguration("test-module").orElseThrow();

                    // then
                    // Parent has its component
                    ServiceInterface parentResult = config.getComponent(ServiceInterface.class);
                    assertEquals("parent", parentResult.getValue());

                    // During module configuration, the child registry still registers its own component
                    // The SearchScope.ALL check happens at configuration time, and at that point
                    // the module's registry is created, so it registers anyway
                    ServiceInterface childResult = moduleConfig.getComponent(ServiceInterface.class);
                    assertEquals("child", childResult.getValue());
                }

                @Test
                void registerIfNotPresentWithSearchScopeAncestorsStillRegistersInChildModule() {
                    // given
                    ServiceImplA parentImpl = new ServiceImplA("parent");
                    ServiceImplB childImplAttempt = new ServiceImplB("child-attempt");

                    // Register in parent
                    testSubject.registerComponent(ServiceInterface.class, c -> parentImpl);

                    // Register module with child registry
                    testSubject.registerModule(
                            new TestModule("test-module").componentRegistry(
                                    childRegistry -> childRegistry.registerIfNotPresent(
                                            ServiceInterface.class,
                                            c -> childImplAttempt,
                                            SearchScope.ANCESTORS
                                    )
                            )
                    );

                    // when
                    Configuration config = testSubject.build(mock());
                    Configuration moduleConfig = config.getModuleConfiguration("test-module").orElseThrow();

                    // then
                    // Parent has its component
                    ServiceInterface parentResult = config.getComponent(ServiceInterface.class);
                    assertEquals("parent", parentResult.getValue());

                    // Similar to SearchScope.ALL, the child still registers its component
                    ServiceInterface childResult = moduleConfig.getComponent(ServiceInterface.class);
                    assertEquals("child-attempt", childResult.getValue());
                }

                @Test
                void registerIfNotPresentDefaultBehaviorWithModules() {
                    // given
                    ServiceImplA parentImpl = new ServiceImplA("parent");
                    ServiceImplB childImpl = new ServiceImplB("child");

                    // Register in parent
                    testSubject.registerComponent(ServiceInterface.class, c -> parentImpl);

                    // Register module with child registry - no explicit SearchScope
                    testSubject.registerModule(
                            new TestModule("test-module").componentRegistry(
                                    childRegistry -> childRegistry.registerIfNotPresent(
                                            ServiceInterface.class,
                                            c -> childImpl
                                    )
                            )
                    );

                    // when
                    Configuration config = testSubject.build(mock());
                    Configuration moduleConfig = config.getModuleConfiguration("test-module").orElseThrow();

                    // then
                    // Parent has its component
                    ServiceInterface parentResult = config.getComponent(ServiceInterface.class);
                    assertEquals("parent", parentResult.getValue());

                    // Child registers its own component with default behavior
                    ServiceInterface childResult = moduleConfig.getComponent(ServiceInterface.class);
                    assertEquals("child", childResult.getValue());
                }
            }
        }

        @Nested
        class RetrieveByTypeReference {

            @Test
            void getOptionalComponentByTypeRef() {
                // given
                GenericServiceImpl<String> exStringService = new GenericServiceImpl<>("value-a");
                GenericServiceImpl<Integer> exIntegerService = new GenericServiceImpl<>(124);

                ComponentDefinition<GenericServiceInterface<String>> stringServiceDef =
                        ComponentDefinition.ofType(new TypeReference<GenericServiceInterface<String>>() {
                                           })
                                           .withInstance(exStringService);
                ComponentDefinition<GenericServiceInterface<Integer>> integerServiceDef =
                        ComponentDefinition.ofType(new TypeReference<GenericServiceInterface<Integer>>() {
                                           })
                                           .withInstance(exIntegerService);

                testSubject.registerComponent(stringServiceDef)
                           .registerComponent(integerServiceDef);

                Configuration config = testSubject.build(mock());

                // when
                Optional<GenericServiceInterface<String>> acStringServiceOptional = config.getOptionalComponent(new TypeReference<>() {
                });
                GenericServiceInterface<String> acStringService = config.getComponent(new TypeReference<>() {
                });
                Optional<GenericServiceInterface<Integer>> acIntegerServiceOptional = config.getOptionalComponent(new TypeReference<>() {
                });
                GenericServiceInterface<Integer> acIntegerService = config.getComponent(new TypeReference<>() {
                });
                Optional<GenericServiceInterface<Double>> acDoubleServiceOptional = config.getOptionalComponent(new TypeReference<>() {
                });

                // then
                assertTrue(acStringServiceOptional.isPresent());
                assertEquals(exStringService, acStringServiceOptional.get());
                assertEquals(exStringService, acStringService);
                assertEquals("value-a", acStringService.getValue());

                assertTrue(acIntegerServiceOptional.isPresent());
                assertEquals(exIntegerService, acIntegerServiceOptional.get());
                assertEquals(exIntegerService, acIntegerService);
                assertEquals(124, acIntegerService.getValue());

                assertFalse(acDoubleServiceOptional.isPresent());
            }
        }

        @Test
        void getComponentByTypeRefAndName() {
            // given
            GenericServiceImpl<String> exStringService = new GenericServiceImpl<>("value-a");
            GenericServiceImpl<Integer> exIntegerService = new GenericServiceImpl<>(124);
            String exComponentName = "service";

            ComponentDefinition<GenericServiceInterface<String>> stringServiceDef =
                    ComponentDefinition.ofTypeAndName(new TypeReference<GenericServiceInterface<String>>() {
                                       }, exComponentName)
                                       .withInstance(exStringService);
            ComponentDefinition<GenericServiceInterface<Integer>> integerServiceDef =
                    ComponentDefinition.ofTypeAndName(new TypeReference<GenericServiceInterface<Integer>>() {
                                       }, exComponentName)
                                       .withInstance(exIntegerService);

            testSubject.registerComponent(stringServiceDef)
                       .registerComponent(integerServiceDef);

            Configuration config = testSubject.build(mock());

            // when
            Optional<GenericServiceInterface<String>> acStringServiceOptional = config.getOptionalComponent(new TypeReference<>() {
            }, exComponentName);
            GenericServiceInterface<String> acStringService = config.getComponent(new TypeReference<>() {
            }, exComponentName);
            Optional<GenericServiceInterface<Integer>> acIntegerServiceOptional = config.getOptionalComponent(new TypeReference<>() {
            }, exComponentName);
            GenericServiceInterface<Integer> acIntegerService = config.getComponent(new TypeReference<>() {
            }, exComponentName);

            // then
            assertTrue(acStringServiceOptional.isPresent());
            assertEquals(exStringService, acStringServiceOptional.get());
            assertEquals(exStringService, acStringService);
            assertEquals("value-a", acStringService.getValue());

            assertTrue(acIntegerServiceOptional.isPresent());
            assertEquals(exIntegerService, acIntegerServiceOptional.get());
            assertEquals(exIntegerService, acIntegerService);
            assertEquals(124, acIntegerService.getValue());
        }

        @Test
        void hasComponentByTypeRef() {
            // given
            GenericServiceImpl<String> exStringService = new GenericServiceImpl<>("value-a");
            GenericServiceImpl<Integer> exIntegerService = new GenericServiceImpl<>(124);

            ComponentDefinition<GenericServiceInterface<String>> stringServiceDef =
                    ComponentDefinition.ofType(new TypeReference<GenericServiceInterface<String>>() {
                                       })
                                       .withInstance(exStringService);
            ComponentDefinition<GenericServiceInterface<Integer>> integerServiceDef =
                    ComponentDefinition.ofType(new TypeReference<GenericServiceInterface<Integer>>() {
                                       })
                                       .withInstance(exIntegerService);

            testSubject.registerComponent(stringServiceDef)
                       .registerComponent(integerServiceDef);

            Configuration config = testSubject.build(mock());

            // when / then
            assertTrue(config.hasComponent(new TypeReference<GenericServiceInterface<String>>() {
            }));
            assertTrue(config.hasComponent(new TypeReference<GenericServiceInterface<Integer>>() {
            }));
            assertFalse(config.hasComponent(new TypeReference<GenericServiceInterface<Double>>() {
            }));
        }

        @Test
        void hasComponentByTypeRefAndName() {
            // given
            GenericServiceImpl<String> exStringService = new GenericServiceImpl<>("value-a");
            GenericServiceImpl<Integer> exIntegerService = new GenericServiceImpl<>(124);
            String exComponentName = "service";

            ComponentDefinition<GenericServiceInterface<String>> stringServiceDef =
                    ComponentDefinition.ofTypeAndName(new TypeReference<GenericServiceInterface<String>>() {
                                       }, exComponentName)
                                       .withInstance(exStringService);
            ComponentDefinition<GenericServiceInterface<Integer>> integerServiceDef =
                    ComponentDefinition.ofTypeAndName(new TypeReference<GenericServiceInterface<Integer>>() {
                                       }, exComponentName)
                                       .withInstance(exIntegerService);

            testSubject.registerComponent(stringServiceDef)
                       .registerComponent(integerServiceDef);

            Configuration config = testSubject.build(mock());

            // when
            Optional<GenericServiceInterface<String>> acStringServiceOptional = config.getOptionalComponent(new TypeReference<>() {
            }, exComponentName);
            GenericServiceInterface<String> acStringService = config.getComponent(new TypeReference<>() {
            }, exComponentName);
            Optional<GenericServiceInterface<Integer>> acIntegerServiceOptional = config.getOptionalComponent(new TypeReference<>() {
            }, exComponentName);
            GenericServiceInterface<Integer> acIntegerService = config.getComponent(new TypeReference<>() {
            }, exComponentName);

            // then
            assertTrue(acStringServiceOptional.isPresent());
            assertEquals(exStringService, acStringServiceOptional.get());
            assertEquals(exStringService, acStringService);
            assertEquals("value-a", acStringService.getValue());

            assertTrue(acIntegerServiceOptional.isPresent());
            assertEquals(exIntegerService, acIntegerServiceOptional.get());
            assertEquals(exIntegerService, acIntegerService);
            assertEquals(124, acIntegerService.getValue());
        }
    }

    @Nested
    class GetComponents {

        @Test
        void returnsEmptyMapWhenNoComponentsOfTypeExist() {
            // given
            testSubject.registerComponent(String.class, c -> "test");

            // when
            Configuration config = testSubject.build(mock());
            Map<String, ServiceInterface> result = config.getComponents(ServiceInterface.class);

            // then
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void returnsUnnamedComponentWithNullKey() {
            // given
            ServiceImplA implementation = new ServiceImplA("test-value");
            testSubject.registerComponent(ServiceInterface.class, c -> implementation);

            // when
            Configuration config = testSubject.build(mock());
            Map<String, ServiceInterface> result = config.getComponents(ServiceInterface.class);

            // then
            assertEquals(1, result.size());
            assertTrue(result.containsKey(null));
            assertSame(implementation, result.get(null));
        }

        @Test
        void returnsNamedComponentsWithTheirNames() {
            // given
            ServiceImplA implA = new ServiceImplA("value-a");
            ServiceImplB implB = new ServiceImplB("value-b");
            testSubject.registerComponent(ServiceInterface.class, "service-a", c -> implA)
                       .registerComponent(ServiceInterface.class, "service-b", c -> implB);

            // when
            Configuration config = testSubject.build(mock());
            Map<String, ServiceInterface> result = config.getComponents(ServiceInterface.class);

            // then
            assertEquals(2, result.size());
            assertTrue(result.containsKey("service-a"));
            assertTrue(result.containsKey("service-b"));
            assertSame(implA, result.get("service-a"));
            assertSame(implB, result.get("service-b"));
        }

        @Test
        void returnsMixOfNamedAndUnnamedComponents() {
            // given
            ServiceImplA unnamedImpl = new ServiceImplA("unnamed");
            ServiceImplB namedImpl = new ServiceImplB("named");
            testSubject.registerComponent(ServiceInterface.class, c -> unnamedImpl)
                       .registerComponent(ServiceInterface.class, "named-service", c -> namedImpl);

            // when
            Configuration config = testSubject.build(mock());
            Map<String, ServiceInterface> result = config.getComponents(ServiceInterface.class);

            // then
            assertEquals(2, result.size());
            assertTrue(result.containsKey(null));
            assertTrue(result.containsKey("named-service"));
            assertSame(unnamedImpl, result.get(null));
            assertSame(namedImpl, result.get("named-service"));
        }

        @Test
        void returnsComponentsFromModuleConfigurations() {
            // given
            ServiceImplA mainImpl = new ServiceImplA("main");
            ServiceImplB moduleImpl = new ServiceImplB("module");

            testSubject.registerComponent(ServiceInterface.class, "main-service", c -> mainImpl)
                       .registerModule(
                               new TestModule("test-module").componentRegistry(
                                       cr -> cr.registerComponent(
                                               ServiceInterface.class, "module-service", c -> moduleImpl
                                       )
                               )
                       );

            // when
            Configuration config = testSubject.build(mock());
            Map<String, ServiceInterface> result = config.getComponents(ServiceInterface.class);

            // then
            assertEquals(2, result.size());
            assertTrue(result.containsKey("main-service"));
            assertTrue(result.containsKey("module-service"));
            assertSame(mainImpl, result.get("main-service"));
            assertSame(moduleImpl, result.get("module-service"));
        }

        @Test
        void returnsComponentsMatchingSubtypes() {
            // given
            ServiceImplA implementation = new ServiceImplA("test-value");
            testSubject.registerComponent(ServiceImplA.class, c -> implementation);

            // when
            Configuration config = testSubject.build(mock());
            Map<String, ServiceInterface> result = config.getComponents(ServiceInterface.class);

            // then
            assertEquals(1, result.size());
            assertSame(implementation, result.values().iterator().next());
        }

        @Test
        void returnsImmutableMap() {
            // given
            ServiceImplA implementation = new ServiceImplA("test-value");
            testSubject.registerComponent(ServiceInterface.class, "service", c -> implementation);

            // when
            Configuration config = testSubject.build(mock());
            Map<String, ServiceInterface> result = config.getComponents(ServiceInterface.class);

            // then
            assertThrows(UnsupportedOperationException.class, () -> result.put("new", new ServiceImplB("new")));
            assertThrows(UnsupportedOperationException.class, () -> result.remove("service"));
            assertThrows(UnsupportedOperationException.class, result::clear);
        }

        @Test
        void doesNotIncludeFactoryComponentsNotYetAccessed() {
            // given
            // Register a component factory that can create components on-demand
            ComponentFactory<ServiceInterface> factory = new ComponentFactory<>() {
                @Override
                public @NonNull Class<ServiceInterface> forType() {
                    return ServiceInterface.class;
                }

                @Override
                public @NonNull Optional<Component<ServiceInterface>> construct(@NonNull String name, @NonNull Configuration config) {
                    // Factory can create components with names like "factory-1", "factory-2", etc.
                    if (name != null && name.startsWith("factory-")) {
                        return Optional.of(new LazyInitializedComponentDefinition<>(
                                new Component.Identifier<>(ServiceInterface.class, name),
                                c -> new ServiceImplA("from-factory-" + name)
                        ));
                    }
                    return Optional.empty();
                }

                @Override
                public void registerShutdownHandlers(@NonNull LifecycleRegistry registry) {
                    // No shutdown needed for this test
                }

                @Override
                public void describeTo(org.axonframework.common.infra.@NonNull ComponentDescriptor descriptor) {
                    descriptor.describeProperty("type", "TestFactory");
                }
            };

            testSubject.registerComponent(ServiceInterface.class, "registered", c -> new ServiceImplA("registered"))
                       .registerFactory(factory);

            // when
            Configuration config = testSubject.build(mock());
            Map<String, ServiceInterface> resultBeforeAccess = config.getComponents(ServiceInterface.class);

            // then
            // Only explicitly registered components are included
            // Factory-created components that haven't been accessed are NOT included
            assertEquals(1, resultBeforeAccess.size());
            assertTrue(resultBeforeAccess.containsKey("registered"));
            assertFalse(resultBeforeAccess.containsKey("factory-1"));

            // when - access a factory component
            ServiceInterface factoryComponent = config.getComponent(ServiceInterface.class, "factory-1");
            Map<String, ServiceInterface> resultAfterAccess = config.getComponents(ServiceInterface.class);

            // then
            // After accessing a factory component, it should now appear in getComponents()
            assertEquals(2, resultAfterAccess.size());
            assertTrue(resultAfterAccess.containsKey("registered"));
            assertTrue(resultAfterAccess.containsKey("factory-1"));
            assertSame(factoryComponent, resultAfterAccess.get("factory-1"));
        }
    }

    @Nested
    class DescribeTo {

        @Test
        void configurerDescribeToDescribesBeingUninitializedComponentsEnhancersAndModules() {
            // given...
            ComponentDescriptor testDescriptor = mock(ComponentDescriptor.class);
            ConfigurationEnhancer testEnhancer = configurer -> {

            };
            TestModule testModule = new TestModule("module");
            //noinspection unchecked
            ComponentFactory<String> testFactory = mock(ComponentFactory.class);

            testSubject.registerComponent(TestComponent.class, c -> TEST_COMPONENT)
                       .registerEnhancer(testEnhancer)
                       .registerModule(testModule)
                       .registerFactory(testFactory);

            // when...
            testSubject.describeTo(testDescriptor);

            // then...
            verify(testDescriptor).describeProperty("initialized", false);
            verify(testDescriptor).describeProperty(eq("components"), isA(Components.class));
            verify(testDescriptor).describeProperty(eq("decorators"), isA(List.class));
            verify(testDescriptor).describeProperty(eq("modules"), eqList(List.of(testModule)));
            //noinspection unchecked
            ArgumentCaptor<Map<String, ConfigurationEnhancer>> enhancerCaptor = ArgumentCaptor.forClass(Map.class);
            verify(testDescriptor).describeProperty(eq("configurerEnhancers"), enhancerCaptor.capture());
            Collection<ConfigurationEnhancer> enhancers = enhancerCaptor.getValue().values();
            assertTrue(enhancers.contains(testEnhancer));
            //noinspection unchecked
            ArgumentCaptor<List<ComponentFactory<?>>> factoryCaptor = ArgumentCaptor.forClass(List.class);
            verify(testDescriptor).describeProperty(eq("factories"), factoryCaptor.capture());
            List<ComponentFactory<?>> factories = factoryCaptor.getValue();
            assertTrue(factories.contains(testFactory));

            // Ensure new fields added to the describeTo implementation are validated
            verifyNoMoreInteractions(testDescriptor);
        }

        @Test
        void configurerDescribeToDescribesBeingInitializedComponentsEnhancersAndModules() {
            // given...
            ComponentDescriptor testDescriptor = mock(ComponentDescriptor.class);
            ConfigurationEnhancer testEnhancer = configurer -> {

            };
            TestModule testModule = new TestModule("module");
            //noinspection unchecked
            ComponentFactory<String> testFactory = mock(ComponentFactory.class);
            when(testFactory.forType()).thenReturn(String.class);

            testSubject.registerComponent(TestComponent.class, c -> TEST_COMPONENT)
                       .registerEnhancer(testEnhancer)
                       .registerModule(testModule)
                       .registerFactory(testFactory);
            // Build initializes the configurer
            testSubject.build(lifecycleRegistry);

            // when...
            testSubject.describeTo(testDescriptor);

            // then...
            verify(testDescriptor).describeProperty("initialized", true);
            verify(testDescriptor).describeProperty(eq("components"), isA(Components.class));
            verify(testDescriptor).describeProperty(eq("decorators"), isA(List.class));
            verify(testDescriptor).describeProperty(eq("modules"), eqList(testModule));
            //noinspection unchecked
            ArgumentCaptor<Map<String, ConfigurationEnhancer>> enhancerCaptor = ArgumentCaptor.forClass(Map.class);
            verify(testDescriptor).describeProperty(eq("configurerEnhancers"), enhancerCaptor.capture());
            Collection<ConfigurationEnhancer> enhancers = enhancerCaptor.getValue().values();
            assertTrue(enhancers.contains(testEnhancer));
            //noinspection unchecked
            ArgumentCaptor<List<ComponentFactory<?>>> factoryCaptor = ArgumentCaptor.forClass(List.class);
            verify(testDescriptor).describeProperty(eq("factories"), factoryCaptor.capture());
            List<ComponentFactory<?>> factories = factoryCaptor.getValue();
            assertTrue(factories.contains(testFactory));

            // Ensure new fields added to the describeTo implementation are validated
            verifyNoMoreInteractions(testDescriptor);
        }

        @Test
        void configurationDescribeToDescribesComponentsAndModules() {
            ComponentDescriptor testDescriptor = mock(ComponentDescriptor.class);

            TestModule testModule = new TestModule("module");

            // The Component is not yet validated, as I am using a mocked ComponentDescriptor.
            testSubject.registerComponent(TestComponent.class, c -> TEST_COMPONENT)
                       .registerModule(testModule.componentRegistry(
                               cr -> cr.registerComponent(TestComponent.class, c -> TEST_COMPONENT)
                       ));

            Configuration result = testSubject.build(mock());

            result.describeTo(testDescriptor);

            verify(testDescriptor).describeProperty(eq("components"), isA(Components.class));
            verify(testDescriptor).describeProperty(eq("modules"), eqList(result.getModuleConfigurations()));

            // Ensure new fields added to the describeTo implementation are validated
            verifyNoMoreInteractions(testDescriptor);
        }

        @SafeVarargs
        private <T> Collection<T> eqList(T... expected) {
            return eqList(List.of(expected));
        }

        private <T> Collection<T> eqList(Collection<T> expected) {
            return argThat(c -> List.copyOf(expected).equals(List.copyOf(c)));
        }
    }

    protected record TestComponent(String state) {

    }

    private record RegisteringConfigurationEnhancer(
            List<ConfigurationEnhancer> invokedEnhancers,
            int order
    ) implements ConfigurationEnhancer {

        @Override
        public void enhance(@NonNull ComponentRegistry registry) {
            invokedEnhancers.add(this);
        }
    }

    private static class TestModule extends BaseModule<TestModule> {

        /**
         * Construct a base module with the given {@code name}.
         *
         * @param name The name of this module. Must not be {@code null}
         */
        public TestModule(@NonNull String name) {
            super(name);
        }
    }

    // Test component hierarchy for exploration tests
    interface ServiceInterface {

        String getValue();
    }

    static class ServiceImplA implements ServiceInterface {

        private final String value;

        ServiceImplA(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }
    }

    static class ServiceImplB implements ServiceInterface {

        private final String value;

        ServiceImplB(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }
    }

    // Test component for retrieval by type reference
    interface GenericServiceInterface<T> {

        T getValue();
    }

    static class GenericServiceImpl<T> implements GenericServiceInterface<T> {

        private final T value;

        GenericServiceImpl(T value) {
            this.value = value;
        }

        @Override
        public T getValue() {
            return value;
        }
    }
}