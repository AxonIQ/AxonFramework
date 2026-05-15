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

package org.axonframework.common.property;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;


class PropertyAccessStrategyTest {

    private final PropertyAccessStrategy mock1 = new StubPropertyAccessStrategy(1000, "mock1");
    private final PropertyAccessStrategy mock2 = new StubPropertyAccessStrategy(1200, "mock2");
    private final PropertyAccessStrategy mock3 = new StubPropertyAccessStrategy(1000, "mock3");
    private final PropertyAccessStrategy mock4 = new StubPropertyAccessStrategy(1000, "mock4");
    private final TestPropertyAccessStrategy testPropertyAccessStrategy = new TestPropertyAccessStrategy();

    @AfterEach
    void setUp() {
        PropertyAccessStrategy.unregister(mock1);
        PropertyAccessStrategy.unregister(mock2);
        PropertyAccessStrategy.unregister(mock3);
        PropertyAccessStrategy.unregister(mock4);
        PropertyAccessStrategy.unregister(testPropertyAccessStrategy);
    }

    @Test
    void beanPropertyAccess() {
        Property<Bean> beanProperty = PropertyAccessStrategy.getProperty(Bean.class, "beanProperty");

        assertNotNull(beanProperty);
        assertEquals("beanProperty", beanProperty.getValue(new Bean()));
    }

    @Test
    void uniformPropertyAccess() {
        Property<Bean> beanProperty = PropertyAccessStrategy.getProperty(Bean.class, "uniformProperty");
        assertNotNull(beanProperty);
        assertEquals("uniformProperty", beanProperty.getValue(new Bean()));
    }

    @Test
    void register() {
        PropertyAccessStrategy.register(testPropertyAccessStrategy);
        Property<Bean> beanProperty = PropertyAccessStrategy.getProperty(Bean.class, "testProperty");

        assertNotNull(beanProperty);
        assertEquals("testGetterInvoked", beanProperty.getValue(new Bean()));
    }

    @Test
    void invocationOrdering() {
        PropertyAccessStrategy.register(mock1);
        PropertyAccessStrategy.register(mock2);

        Property<Bean> beanProperty = PropertyAccessStrategy.getProperty(Bean.class, "testProperty");

        assertNotNull(beanProperty);
        assertEquals("mock2", beanProperty.getValue(new Bean()));
    }

    @Test
    void invocationOrdering_EqualPriorityUsesClassName() {
        PropertyAccessStrategy.register(mock3);
        PropertyAccessStrategy.register(mock4);
        assertEquals("mock3",
                     PropertyAccessStrategy.getProperty(Bean.class, "testProperty").getValue(new Bean()));
    }

    @Test
    void propertyValueCanBeNull() {
        PropertyAccessStrategy strategy = new StubPropertyAccessStrategy(1000, null);
        assertNull(strategy.propertyFor(Bean.class, "testProperty").getValue(new Bean(null, null)));

    }

    static class TestPropertyAccessStrategy extends PropertyAccessStrategy {

        @Override
        protected int getPriority() {
            return Integer.MAX_VALUE;
        }

        @Override
        @NonNull
        protected <T> Property<T> propertyFor(@NonNull Class<? extends T> targetClass, @Nullable String property) {
            return new StubProperty<>("testGetterInvoked");
        }

        private static class StubProperty<T> implements Property<T> {

            private final String value;

            private StubProperty(String value) {
                this.value = value;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <V> V getValue(T target) {
                return (V) value;
            }
        }
    }


    static class Bean {

        private final String beanProperty;
        private final String uniformProperty;

        Bean() {
            this("beanProperty", "uniformProperty");
        }

        Bean(String beanProperty, String uniformProperty) {
            this.beanProperty = beanProperty;
            this.uniformProperty = uniformProperty;
        }

        public String getBeanProperty() {
            return beanProperty;
        }

        public String uniformProperty() {
            return uniformProperty;
        }
    }

    private static class StubPropertyAccessStrategy extends PropertyAccessStrategy {

        private final int priority;
        private final String value;

        public StubPropertyAccessStrategy(int priority, String value) {
            this.priority = priority;
            this.value = value;
        }

        @Override
        protected int getPriority() {
            return priority;
        }

        @Override
        @NonNull
        protected <T> Property<T> propertyFor(@NonNull Class<? extends T> targetClass, @Nullable String property) {
            return new TestPropertyAccessStrategy.StubProperty<>(value);
        }
    }
}

