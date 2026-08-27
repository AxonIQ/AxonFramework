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

import org.axonframework.common.ObjectUtils;
import org.axonframework.common.annotation.Internal;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects the name of the operating system user account running the JVM.
 * <p>
 * The detected name is memoized after the first call, as it is not expected to change during the lifetime of the JVM.
 * <p>
 * If the {@code user.name} system property is not available, this falls back to {@code "unknown"}. This method never
 * throws and never returns {@code null}.
 *
 * @author Steven van Beelen
 * @since 5.3.2
 */
@Internal
public class MachineUserName {

    private static final Logger logger = LoggerFactory.getLogger(MachineUserName.class);

    private static final String USER_NAME_PROPERTY = "user.name";

    @Nullable
    private static String machineUserName;

    /**
     * Returns the name of the operating system user account running the JVM. If it has not been detected yet, it will
     * be detected first.
     *
     * @return the name of the operating system user account, or {@code "unknown"} if it could not be detected
     */
    public static String get() {
        if (machineUserName == null) {
            machineUserName = detect();
        }
        return machineUserName;
    }

    private static String detect() {
        try {
            return ObjectUtils.getOrDefault(System.getProperty(USER_NAME_PROPERTY), "unknown");
        } catch (Exception e) {
            logger.debug("Failed to detect machine user name.", e);
            return "unknown";
        }
    }

    private MachineUserName() {
        // Utility class
    }
}
