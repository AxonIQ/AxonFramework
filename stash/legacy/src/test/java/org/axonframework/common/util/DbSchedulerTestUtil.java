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

package org.axonframework.common.util;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerBuilder;
import com.github.kagkarlsson.scheduler.task.Task;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import javax.sql.DataSource;

/**
 * Utility methods for testing db-scheduler integration.
 *
 * @author Gerard Klijs
 */
public abstract class DbSchedulerTestUtil {

    private DbSchedulerTestUtil() {
        //prevent instantiation
    }

    /**
     * Recreates the table used to store the tasks, this way we are both sure no old tasks are interfering, and the
     * table is properly created.
     *
     * @param dataSource a {@link DataSource} instance, which is used in the constructor of the {@link Scheduler}
     */
    public static void reCreateTable(DataSource dataSource) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        try (PreparedStatement statement = connection.prepareStatement("drop table if exists scheduled_tasks;")) {
            statement.execute();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "create table scheduled_tasks (\n"
                                     + "  task_name varchar(40) not null,\n"
                                     + "  task_instance varchar(40) not null,\n"
                                     + "  task_data blob,\n"
                                     + "  execution_time timestamp(6) not null,\n"
                                     + "  picked BOOLEAN not null,\n"
                                     + "  picked_by varchar(50),\n"
                                     + "  last_success timestamp(6) null,\n"
                                     + "  last_failure timestamp(6) null,\n"
                                     + "  consecutive_failures INT,\n"
                                     + "  last_heartbeat timestamp(6) null,\n"
                                     + "  version BIGINT not null,\n"
                                     + "  PRIMARY KEY (task_name, task_instance),\n"
                                     + ")")) {
            statement.execute();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates and starts a scheduler
     *
     * @param dataSource a {@link DataSource} instance, which should have a {@code scheduled_tasks} table
     * @param task       the {@link Task} we want to test
     * @return a {@link Scheduler} that is started
     */
    public static Scheduler getScheduler(DataSource dataSource, Task<?> task) {
        Scheduler scheduler = new SchedulerBuilder(dataSource, Collections.singletonList(task))
                .threads(2)
                .pollingInterval(Duration.ofMillis(50L))
                .build();
        return scheduler;
    }
}
