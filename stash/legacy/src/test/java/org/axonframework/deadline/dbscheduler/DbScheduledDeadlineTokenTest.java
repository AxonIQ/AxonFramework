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

import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DbScheduledDeadlineTokenTest {

    @Test
    void equalsIfSameUuidIsUsed() {
        String uuid = UUID.randomUUID().toString();
        TaskInstanceId one = new DbSchedulerDeadlineToken(uuid);
        TaskInstanceId other = new DbSchedulerDeadlineToken(uuid);

        assertEquals(one, other);
        assertEquals(one.toString(), other.toString());
        assertEquals(one.hashCode(), other.hashCode());
    }

    @Test
    void notEqualsIfDifferentUuidIsUsed() {
        TaskInstanceId one = new DbSchedulerDeadlineToken(UUID.randomUUID().toString());
        TaskInstanceId other = new DbSchedulerDeadlineToken(UUID.randomUUID().toString());

        assertNotEquals(one, other);
        assertNotEquals(one.toString(), other.toString());
        assertNotEquals(one.hashCode(), other.hashCode());
    }
}
