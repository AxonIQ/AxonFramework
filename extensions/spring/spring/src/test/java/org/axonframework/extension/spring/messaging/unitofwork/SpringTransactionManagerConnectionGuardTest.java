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

package org.axonframework.extension.spring.messaging.unitofwork;

import org.axonframework.common.jdbc.ConnectionExecutor;
import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.jta.JtaTransactionManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that the {@link SpringTransactionManager} only hands out connections that take part in a transaction.
 *
 * @author John Hendrikx
 */
@ExtendWith(MockitoExtension.class)
class SpringTransactionManagerConnectionGuardTest {

    @Mock
    private ConnectionProvider connectionProvider;

    @Mock
    private Connection connection;

    @Mock
    private ProcessingLifecycle processingLifecycle;

    @Captor
    private ArgumentCaptor<Consumer<ProcessingContext>> preInvocation;

    @BeforeEach
    void setUp() throws SQLException {
        when(connectionProvider.getConnection()).thenReturn(connection);
    }

    @Test
    void shouldProvideAConnectionThatIsNotAutoCommitting(@Mock PlatformTransactionManager ptm) throws Exception {
        when(connection.getAutoCommit()).thenReturn(false);

        Connection provided = useConnection(new SpringTransactionManager(ptm, null, connectionProvider));

        assertThat(provided).isSameAs(connection);
    }

    @Test
    void shouldRefuseAndCloseAConnectionThatAutoCommits(@Mock PlatformTransactionManager ptm) throws Exception {
        when(connection.getAutoCommit()).thenReturn(true);

        assertThatThrownBy(() -> useConnection(new SpringTransactionManager(ptm, null, connectionProvider)))
                .hasRootCauseInstanceOf(NonTransactionalConnectionException.class);
        verify(connection).close();
    }

    @Test
    void shouldNotCheckConnectionsWhenUsingJta(@Mock JtaTransactionManager jta) throws Exception {
        Connection provided = useConnection(new SpringTransactionManager(jta, null, connectionProvider));

        assertThat(provided).isSameAs(connection);
    }

    private Connection useConnection(SpringTransactionManager testSubject) {
        StubProcessingContext context = new StubProcessingContext();

        testSubject.attachToProcessingLifecycle(processingLifecycle);
        verify(processingLifecycle).runOnPreInvocation(preInvocation.capture());
        preInvocation.getValue().accept(context);

        Supplier<ConnectionExecutor> supplier = context.getResource(JdbcTransactionalExecutorProvider.SUPPLIER_KEY);

        try {
            return supplier.get().apply(c -> c).join();
        }
        catch (CompletionException e) {
            throw e;
        }
    }
}
