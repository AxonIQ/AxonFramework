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

package org.axonframework.common.jdbc;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link PhysicalConnectionAwareExecutor}.
 *
 * @author John Hendrikx
 */
class PhysicalConnectionAwareExecutorTest {

    /**
     * A connection whose physical state is controlled by the test. The connection itself is a dummy.
     */
    private static final class FakeConnection implements PhysicalConnectionAware {

        private final AtomicBoolean physical = new AtomicBoolean();
        private final AtomicInteger shieldedDecisions = new AtomicInteger();

        private Runnable beforeShieldedDecision = () -> {
        };

        Connection asConnection() {
            return (Connection) Proxy.newProxyInstance(
                FakeConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class, PhysicalConnectionAware.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == PhysicalConnectionAware.class) {
                        return method.invoke(this, args);
                    }

                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    };
                }
            );
        }

        @Override
        public boolean isPhysicallyConnected() {
            return physical.get();
        }

        @Override
        public <R> R withPhysicalConnectionShielded(Function<Boolean, R> decision) {
            beforeShieldedDecision.run();
            shieldedDecisions.incrementAndGet();

            return decision.apply(physical.get());
        }
    }

    @Nested
    class WhenConstructing {

        @Test
        void shouldRejectAConnectionThatIsNotPhysicalConnectionAware() {
            // given
            Connection plain = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> null
            );

            // when / then
            assertThatThrownBy(() -> new PhysicalConnectionAwareExecutor(plain))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldRejectANullConnection() {
            // when / then
            assertThatThrownBy(() -> new PhysicalConnectionAwareExecutor(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class WhenDeciding {

        @Test
        void shouldReportNotActiveWhileTheConnectionIsNotPhysical() {
            // given
            FakeConnection fake = new FakeConnection();
            PhysicalConnectionAwareExecutor testSubject = new PhysicalConnectionAwareExecutor(fake.asConnection());

            // when
            Boolean active = testSubject.withMaterializationShielded(isActive -> isActive);

            // then
            assertThat(active).isFalse();
            assertThat(fake.shieldedDecisions).hasValue(1);
        }

        @Test
        void shouldActivateAndReportActiveOnceTheConnectionIsPhysical() {
            // given
            FakeConnection fake = new FakeConnection();
            PhysicalConnectionAwareExecutor testSubject = new PhysicalConnectionAwareExecutor(fake.asConnection());

            fake.physical.set(true);

            // when
            Boolean active = testSubject.withMaterializationShielded(isActive -> isActive);

            // then
            assertThat(active).isTrue();

            Boolean stillActive = testSubject.withMaterializationShielded(isActive -> isActive);

            assertThat(stillActive).isTrue();
        }

        @Test
        void shouldDecideAgainWhenAPhysicalConnectionWasTakenWhileDeciding() {
            // given
            FakeConnection fake = new FakeConnection();
            PhysicalConnectionAwareExecutor testSubject = new PhysicalConnectionAwareExecutor(fake.asConnection());

            fake.beforeShieldedDecision = () -> {
                fake.physical.set(true);
                fake.beforeShieldedDecision = () -> {
                };
            };

            // when
            Boolean active = testSubject.withMaterializationShielded(isActive -> isActive);

            // then
            assertThat(active).isTrue();
        }

        @Test
        void shouldRejectANullDecision() {
            // given
            PhysicalConnectionAwareExecutor testSubject =
                new PhysicalConnectionAwareExecutor(new FakeConnection().asConnection());

            // when / then
            assertThatThrownBy(() -> testSubject.withMaterializationShielded(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class WhenApplying {

        @Test
        void shouldUseTheConnectionItWasGiven() {
            // given
            FakeConnection fake = new FakeConnection();
            Connection connection = fake.asConnection();
            PhysicalConnectionAwareExecutor testSubject = new PhysicalConnectionAwareExecutor(connection);

            // when
            Connection used = testSubject.apply(c -> c).join();

            // then
            assertThat(used).isSameAs(connection);
        }
    }
}
