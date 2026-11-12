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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link ConnectionExecutor}.
 *
 * @author John Hendrikx
 */
class ConnectionExecutorTest {

    private final Connection connection = (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> null
    );

    @Test
    void shouldBeActiveFromTheStartWhenCreatedForAConnection() {
        // given
        ConnectionExecutor testSubject = new ConnectionExecutor(connection);

        // when
        Boolean active = testSubject.withMaterializationShielded(isActive -> isActive);

        // then
        assertThat(active).isTrue();
    }

    @Test
    void shouldUseTheConnectionItWasCreatedFor() {
        // given
        ConnectionExecutor testSubject = new ConnectionExecutor(connection);

        // when
        Connection used = testSubject.apply(c -> c).join();

        // then
        assertThat(used).isSameAs(connection);
    }

    @Test
    void shouldNotBeActiveUntilUsedWhenCreatedForAProvider() {
        // given
        ConnectionExecutor testSubject = new ConnectionExecutor(() -> connection);

        // when
        Boolean activeBefore = testSubject.withMaterializationShielded(isActive -> isActive);

        testSubject.apply(c -> c).join();

        Boolean activeAfter = testSubject.withMaterializationShielded(isActive -> isActive);

        // then
        assertThat(activeBefore).isFalse();
        assertThat(activeAfter).isTrue();
    }

    @Test
    void shouldRejectANullConnection() {
        // when / then
        assertThatThrownBy(() -> new ConnectionExecutor((Connection) null)).isInstanceOf(NullPointerException.class);
    }
}
