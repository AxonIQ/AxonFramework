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

package org.axonframework.test.deadline;

import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.messaging.core.Scope;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StubDeadlineManagerTest {

    private StubDeadlineManager testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new StubDeadlineManager();
    }

    @Disabled("TODO reintegrate as part of #3065")
    @Test
    void messagesCarryTriggerTimestamp() throws Exception {
        Instant triggerTime = Instant.now().plusSeconds(60);
        MockScope.execute(() ->
                                  testSubject.schedule(triggerTime, "gone")
        );
        List<DeadlineMessage> triggered = new ArrayList<>();
        testSubject.advanceTimeBy(Duration.ofMinutes(75), (s, message) -> triggered.add(message));

        assertEquals(1, triggered.size());
        assertEquals(triggerTime, triggered.get(0).timestamp());
    }

    private static class MockScope extends Scope {

        private static final MockScope instance = new MockScope();

        public static void execute(Runnable task) throws Exception {
            instance.executeWithResult(() -> {
                task.run();
                return null;
            });
        }

        @Override
        public ScopeDescriptor describeScope() {
            return (ScopeDescriptor) () -> "Mock";
        }
    }
}
