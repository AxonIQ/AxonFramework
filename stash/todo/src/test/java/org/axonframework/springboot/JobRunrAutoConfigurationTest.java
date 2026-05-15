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

package org.axonframework.springboot;

import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.jobrunr.JobRunrDeadlineManager;
import org.axonframework.messaging.eventhandling.scheduling.EventScheduler;
import org.axonframework.messaging.eventhandling.scheduling.jobrunr.JobRunrEventScheduler;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Disabled("TODO #3104 & #3065")
class JobRunrAutoConfigurationTest {

    @Test
    void eventSchedulerAndDeadlineManagercreated() {
        new ApplicationContextRunner()
                .withUserConfiguration(DefaultContext.class)
                .run(context -> {
                    EventScheduler eventScheduler = context.getBean(EventScheduler.class);
                    assertNotNull(eventScheduler);
                    assertTrue(eventScheduler instanceof JobRunrEventScheduler);
                    DeadlineManager deadlineManager = context.getBean(DeadlineManager.class);
                    assertNotNull(deadlineManager);
                    assertTrue(deadlineManager instanceof JobRunrDeadlineManager);
                });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class DefaultContext {
        @Bean
        public JobScheduler jobScheduler(){
            return mock(JobScheduler.class);
        }
    }
}
