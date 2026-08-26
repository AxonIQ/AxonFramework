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

package org.axonframework.integrationtests.deadline;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.DeadlineManagerSpanFactory;
import org.axonframework.deadline.SimpleDeadlineManager;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.ScopeAwareProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.*;
import org.mockito.junit.jupiter.*;

import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.Mockito.*;

@Disabled("TODO #3065 - Revisit Deadline support")
@ExtendWith(MockitoExtension.class)
class SimpleDeadlineManagerTest extends AbstractDeadlineManagerTestSuite {

    @Override
    public DeadlineManager buildDeadlineManager(Configuration configuration) {
        return SimpleDeadlineManager.builder()
//                                    .scopeAwareProvider(new ConfigurationScopeAwareProvider(configuration))
                                    .spanFactory(configuration.getComponent(DeadlineManagerSpanFactory.class))
                                    .messageNameResolver(new ClassBasedMessageTypeResolver())
                                    .build();
    }

    @Test
    void shutdownInvokesExecutorServiceShutdown(@Mock ScopeAwareProvider scopeAwareProvider,
                                                @Mock ScheduledExecutorService scheduledExecutorService) {
        SimpleDeadlineManager testSubject = SimpleDeadlineManager.builder()
                                                                 .scopeAwareProvider(scopeAwareProvider)
                                                                 .scheduledExecutorService(scheduledExecutorService)
                                                                 .build();

        testSubject.shutdown();

        verify(scheduledExecutorService).shutdown();
    }
}
