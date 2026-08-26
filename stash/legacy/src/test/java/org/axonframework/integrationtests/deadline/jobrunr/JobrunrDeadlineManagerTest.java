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

package org.axonframework.integrationtests.deadline.jobrunr;

import org.axonframework.messaging.core.unitofwork.transaction.NoTransactionManager;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.DeadlineManagerSpanFactory;
import org.axonframework.deadline.jobrunr.JobRunrDeadlineManager;
import org.axonframework.integrationtests.deadline.AbstractDeadlineManagerTestSuite;
import org.axonframework.messaging.core.ScopeAwareProvider;
import org.axonframework.deadline.TestScopeDescriptor;
import org.axonframework.conversion.json.JacksonSerializer;
import org.jobrunr.configuration.JobRunr;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.*;
import org.mockito.junit.jupiter.*;

import java.time.Duration;
import java.util.Objects;

import static org.jobrunr.server.BackgroundJobServerConfiguration.usingStandardBackgroundJobServerConfiguration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Disabled("TODO #3065 - Revisit Deadline support")
@ExtendWith(MockitoExtension.class)
class JobrunrDeadlineManagerTest extends AbstractDeadlineManagerTestSuite {

    private BackgroundJobServer backgroundJobServer;

    @AfterEach
    void cleanUp() {
        if (!Objects.isNull(backgroundJobServer)) {
            backgroundJobServer.stop();
            backgroundJobServer = null;
        }
    }

    @Override
    public DeadlineManager buildDeadlineManager(Configuration configuration) {
        StorageProvider storageProvider = new InMemoryStorageProvider();
        JobScheduler scheduler = new JobScheduler(storageProvider);
        JobRunrDeadlineManager manager = JobRunrDeadlineManager
                .builder()
                .jobScheduler(scheduler)
//                .scopeAwareProvider(new ConfigurationScopeAwareProvider(configuration))
                .serializer(JacksonSerializer.defaultSerializer())
                .transactionManager(NoTransactionManager.INSTANCE)
                .spanFactory(configuration.getComponent(DeadlineManagerSpanFactory.class))
                .build();
        JobRunr.configure()
               .useJobActivator(new SimpleActivator(spy(manager)))
               .useStorageProvider(storageProvider)
               .useBackgroundJobServer(
                       usingStandardBackgroundJobServerConfiguration().andPollInterval(Duration.ofMillis(200))
               )
               .initialize();
        backgroundJobServer = JobRunr.getBackgroundJobServer();
        return manager;
    }

    @Test
    void shutdownInvokesSchedulerShutdown(@Mock ScopeAwareProvider scopeAwareProvider) {
        JobScheduler scheduler = spy(new JobScheduler(new InMemoryStorageProvider()));
        JobRunrDeadlineManager testSubject = JobRunrDeadlineManager.builder()
                                                                   .jobScheduler(scheduler)
                                                                   .scopeAwareProvider(scopeAwareProvider)
                                                                   .serializer(JacksonSerializer.defaultSerializer())
                                                                   .transactionManager(NoTransactionManager.INSTANCE)
                                                                   .build();

        testSubject.shutdown();

        verify(scheduler).shutdown();
    }

    @Override
    @Test
    @Disabled("Cancel all within scope is not implemented for the non pro version.")
    public void deadlineCancellationWithinScopeOnSaga() {
    }

    @Override
    @Test
    @Disabled("Cancel all is not implemented for the non pro version.")
    public void deadlineCancelAllOnSagaIsCorrectlyTraced() {
    }

    @Test
    void doNotThrowIllegalJobStateChangeExceptionForAnAlreadyDeletedJob() {
        DeadlineManager testSubject = configuration.getComponent(DeadlineManager.class);

        String deadlineName = "doubleDeleteDoesNotThrowException";
        String scheduleId = testSubject.schedule(Duration.ofMinutes(15), deadlineName, null,
                                                 new TestScopeDescriptor("aggregateType", "aggregateId"));
        testSubject.cancelSchedule(deadlineName, scheduleId);
        assertDoesNotThrow(() -> testSubject.cancelSchedule(deadlineName, scheduleId));
    }
}
