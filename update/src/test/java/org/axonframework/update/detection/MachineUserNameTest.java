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

package org.axonframework.update.detection;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link MachineUserName}.
 *
 * @author Steven van Beelen
 */
class MachineUserNameTest {

    @Test
    void detectsNonBlankMachineUserName() {
        String machineUserName = MachineUserName.get();

        assertNotNull(machineUserName);
        assertFalse(machineUserName.isBlank());
    }

    @Test
    void resultIsMemoized() {
        assertSame(MachineUserName.get(), MachineUserName.get());
    }
}
