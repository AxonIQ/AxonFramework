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

package org.axonframework.deadline.dbscheduler;

import com.github.kagkarlsson.scheduler.Scheduler;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.core.ScopeAwareProvider;
import org.axonframework.conversion.json.JacksonSerializer;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DbSchedulerDeadlineManagerBuilderTest {

    private DbSchedulerDeadlineManager.Builder builder;
    private final Scheduler scheduler = mock(Scheduler.class);
    private final TransactionManager transactionManager = mock(TransactionManager.class);
    private final ScopeAwareProvider scopeAwareProvider = mock(ScopeAwareProvider.class);

    @BeforeEach
    void newBuilder() {
        builder = DbSchedulerDeadlineManager.builder();
    }

    @Test
    void whenAllPropertiesAreSetCreatesManager() {
        DbSchedulerDeadlineManager manager = builder.scopeAwareProvider(scopeAwareProvider)
                                                    .transactionManager(transactionManager)
                                                    .scheduler(scheduler)
                                                    .serializer(JacksonSerializer.defaultSerializer())
                                                    .build();

        assertNotNull(manager);
    }

    @Test
    void validateNeedsAllPropertiesSet() {
        builder.scheduler(scheduler)
               .transactionManager(transactionManager);
        assertThrows(AxonConfigurationException.class, () -> builder.build());
    }

    @Test
    void whenSettingSchedulerWithNullThrowError() {
        assertThrows(AxonConfigurationException.class, () -> builder.scheduler(null));
    }

    @Test
    void whenSettingTransactionManagerWithNullThrowError() {
        assertThrows(AxonConfigurationException.class, () -> builder.transactionManager(null));
    }

    @Test
    void whenSettingScopeAwareProviderWithNullThrowError() {
        assertThrows(AxonConfigurationException.class, () -> builder.scopeAwareProvider(null));
    }
}
