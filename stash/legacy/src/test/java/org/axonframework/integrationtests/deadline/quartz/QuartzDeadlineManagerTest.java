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

package org.axonframework.integrationtests.deadline.quartz;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.deadline.DeadlineException;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.DeadlineManagerSpanFactory;
import org.axonframework.deadline.quartz.QuartzDeadlineManager;
import org.axonframework.integrationtests.deadline.AbstractDeadlineManagerTestSuite;
import org.axonframework.messaging.core.ScopeAwareProvider;
import org.axonframework.conversion.json.JacksonSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.*;
import org.mockito.junit.jupiter.*;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Disabled("TODO #3065 - Revisit Deadline support")
@ExtendWith(MockitoExtension.class)
class QuartzDeadlineManagerTest extends AbstractDeadlineManagerTestSuite {

    @Override
    public DeadlineManager buildDeadlineManager(Configuration configuration) {
        try {
            Scheduler scheduler = new StdSchedulerFactory().getScheduler();
            QuartzDeadlineManager quartzDeadlineManager =
                    QuartzDeadlineManager.builder()
                                         .scheduler(scheduler)
//                                         .scopeAwareProvider(new ConfigurationScopeAwareProvider(configuration))
                                         .serializer(JacksonSerializer.defaultSerializer())
                                         .spanFactory(configuration.getComponent(DeadlineManagerSpanFactory.class))
                                         .build();
            scheduler.start();
            return quartzDeadlineManager;
        } catch (SchedulerException e) {
            throw new AxonConfigurationException("Unable to configure quartz scheduler", e);
        }
    }

    @Test
    void shutdownInvokesSchedulerShutdown(@Mock ScopeAwareProvider scopeAwareProvider) throws SchedulerException {
        Scheduler scheduler = spy(new StdSchedulerFactory().getScheduler());
        QuartzDeadlineManager testSubject = QuartzDeadlineManager.builder()
                                                                 .scopeAwareProvider(scopeAwareProvider)
                                                                 .scheduler(scheduler)
                                                                 .serializer(JacksonSerializer.defaultSerializer())
                                                                 .build();

        testSubject.shutdown();

        verify(scheduler).shutdown(true);
    }

    @Test
    void shutdownFailureResultsInDeadlineException(@Mock ScopeAwareProvider scopeAwareProvider)
            throws SchedulerException {
        Scheduler scheduler = spy(new StdSchedulerFactory().getScheduler());
        doAnswer(invocation -> {
            throw new SchedulerException();
        }).when(scheduler).shutdown(true);
        QuartzDeadlineManager testSubject = QuartzDeadlineManager.builder()
                                                                 .scopeAwareProvider(scopeAwareProvider)
                                                                 .scheduler(scheduler)
                                                                 .serializer(JacksonSerializer.defaultSerializer())
                                                                 .build();

        assertThrows(DeadlineException.class, testSubject::shutdown);
    }

    @Test
    void buildWithoutSchedulerThrowsAxonConfigurationException() {
        ScopeAwareProvider scopeAwareProvider = mock(ScopeAwareProvider.class);
        QuartzDeadlineManager.Builder builderTestSubject =
                QuartzDeadlineManager.builder()
                                     .scopeAwareProvider(scopeAwareProvider)
                                     .serializer(JacksonSerializer.defaultSerializer());

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }

    @Test
    void buildWithoutScopeAwareProviderThrowsAxonConfigurationException() {
        Scheduler scheduler = mock(Scheduler.class);
        QuartzDeadlineManager.Builder builderTestSubject =
                QuartzDeadlineManager.builder()
                                     .scheduler(scheduler)
                                     .serializer(JacksonSerializer.defaultSerializer());

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }
}
