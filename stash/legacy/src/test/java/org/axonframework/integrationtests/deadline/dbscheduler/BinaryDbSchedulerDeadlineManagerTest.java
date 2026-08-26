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

package org.axonframework.integrationtests.deadline.dbscheduler;

import com.github.kagkarlsson.scheduler.Scheduler;
import org.axonframework.messaging.core.unitofwork.transaction.NoTransactionManager;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.DeadlineManagerSpanFactory;
import org.axonframework.deadline.dbscheduler.DbSchedulerDeadlineManager;
import org.axonframework.deadline.dbscheduler.DbSchedulerDeadlineManagerSupplier;
import org.axonframework.integrationtests.deadline.AbstractDeadlineManagerTestSuite;
import org.axonframework.conversion.json.JacksonSerializer;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.junit.jupiter.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Objects;
import javax.sql.DataSource;

import static org.axonframework.common.util.DbSchedulerTestUtil.getScheduler;
import static org.axonframework.common.util.DbSchedulerTestUtil.reCreateTable;

@Disabled("TODO #3065 - Revisit Deadline support")
@ContextConfiguration
@ExtendWith(MockitoExtension.class)
@ExtendWith(SpringExtension.class)
class BinaryDbSchedulerDeadlineManagerTest extends AbstractDeadlineManagerTestSuite {

    @Autowired
    private DataSource dataSource;
    private Scheduler scheduler;

    @AfterEach
    void cleanUp() {
        if (!Objects.isNull(scheduler)) {
            scheduler.stop();
            scheduler = null;
        }
    }

    @Override
    public DeadlineManager buildDeadlineManager(Configuration configuration) {
        reCreateTable(dataSource);
        DbSchedulerDeadlineManagerSupplier supplier = new DbSchedulerDeadlineManagerSupplier();
        scheduler = getScheduler(dataSource, DbSchedulerDeadlineManager.binaryTask(supplier));
        DbSchedulerDeadlineManager deadlineManager = DbSchedulerDeadlineManager
                .builder()
                .scheduler(scheduler)
//                .scopeAwareProvider(new ConfigurationScopeAwareProvider(configuration))
                .serializer(JacksonSerializer.defaultSerializer())
                .transactionManager(NoTransactionManager.INSTANCE)
                .spanFactory(configuration.getComponent(DeadlineManagerSpanFactory.class))
                .build();
        supplier.set(deadlineManager);
        return deadlineManager;
    }

    @org.springframework.context.annotation.Configuration
    public static class Context {

        @SuppressWarnings("Duplicates")
        @Bean
        public DataSource dataSource() {
            JDBCDataSource dataSource = new JDBCDataSource();
            dataSource.setUrl("jdbc:hsqldb:mem:testdb");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }
    }
}
