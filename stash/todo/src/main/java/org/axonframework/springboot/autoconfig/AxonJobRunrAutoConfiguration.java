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

package org.axonframework.springboot.autoconfig;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.config.ConfigurationScopeAwareProvider;
import org.axonframework.conversion.Serializer;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.DeadlineManagerSpanFactory;
import org.axonframework.deadline.jobrunr.JobRunrDeadlineManager;
import org.axonframework.messaging.core.ScopeAwareProvider;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.scheduling.EventScheduler;
import org.axonframework.messaging.eventhandling.scheduling.jobrunr.JobRunrEventScheduler;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto configuration class for the deadline manager and event scheduler using JobRunr.
 *
 * @author Gerard Klijs
 * @since 4.8.0
 */
@AutoConfiguration
@ConditionalOnBean(JobScheduler.class)
@AutoConfigureAfter(
        value = {AxonTracingAutoConfiguration.class},
        name = {"org.jobrunr.spring.autoconfigure.JobRunrAutoConfiguration"}
)
public class AxonJobRunrAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventScheduler eventScheduler(
            JobScheduler jobScheduler,
            @Qualifier("eventSerializer") Serializer serializer,
            TransactionManager transactionManager,
            EventBus eventBus) {
        return JobRunrEventScheduler.builder()
                                    .jobScheduler(jobScheduler)
                                    .serializer(serializer)
                                    .transactionManager(transactionManager)
                                    .eventBus(eventBus)
                                    .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadlineManager deadlineManager(
            JobScheduler jobScheduler,
            Configuration configuration,
            @Qualifier("eventSerializer") Serializer serializer,
            TransactionManager transactionManager,
            DeadlineManagerSpanFactory spanFactory
    ) {
        ScopeAwareProvider scopeAwareProvider = new ConfigurationScopeAwareProvider(configuration);
        return JobRunrDeadlineManager.builder()
                                     .jobScheduler(jobScheduler)
                                     .scopeAwareProvider(scopeAwareProvider)
                                     .serializer(serializer)
                                     .transactionManager(transactionManager)
                                     .spanFactory(spanFactory)
                                     .build();
    }
}
