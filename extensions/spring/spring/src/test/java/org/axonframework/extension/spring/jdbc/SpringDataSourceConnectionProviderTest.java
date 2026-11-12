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

package org.axonframework.extension.spring.jdbc;

import org.axonframework.common.jdbc.PhysicalConnectionAware;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link SpringDataSourceConnectionProvider}.
 *
 * @author John Hendrikx
 */
class SpringDataSourceConnectionProviderTest {

    private final DriverManagerDataSource plain =
        new DriverManagerDataSource("jdbc:hsqldb:mem:" + UUID.randomUUID(), "SA", "");

    @Test
    void shouldProvideAConnectionThatIsNotPhysicalConnectionAwareForAPlainDataSource() throws Exception {
        // given
        SpringDataSourceConnectionProvider testSubject = new SpringDataSourceConnectionProvider(plain);

        // when
        Connection connection = testSubject.getConnection();

        // then
        assertThat(connection).isNotInstanceOf(PhysicalConnectionAware.class);

        connection.close();
    }

    @Test
    void shouldKeepAConnectionPhysicalConnectionAwareThatIsAlready() throws Exception {
        // given
        SpringDataSourceConnectionProvider testSubject =
            new SpringDataSourceConnectionProvider(new TrackingLazyConnectionDataSource(plain));

        // when
        Connection connection = testSubject.getConnection();

        // then
        assertThat(connection).isInstanceOf(PhysicalConnectionAware.class);
        assertThat(((PhysicalConnectionAware) connection).isPhysicallyConnected()).isFalse();

        connection.createStatement().close();

        assertThat(((PhysicalConnectionAware) connection).isPhysicallyConnected()).isTrue();

        connection.close();
    }
}
