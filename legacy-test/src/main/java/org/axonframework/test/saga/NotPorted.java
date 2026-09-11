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

package org.axonframework.test.saga;

/**
 * The failures reported by the parts of the Axon Framework 4 saga fixture that are declared but not yet backed by
 * anything.
 * <p>
 * The declarations are kept so an Axon Framework 4 test suite still compiles, and so the day deadlines are ported the
 * change is to the bodies rather than to the API. Each of those bodies holds the Axon Framework 4 source as a comment.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
final class NotPorted {

    private NotPorted() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Reports that the given {@code method} depends on deadlines or the event scheduler, neither of which
     * {@code axon-legacy} carries yet.
     *
     * @param method the name of the method that was called
     * @return the exception to throw
     */
    static UnsupportedOperationException deadlines(String method) {
        return new UnsupportedOperationException(
                "[" + method + "] is not supported: deadlines and the event scheduler have not been ported into "
                        + "axon-legacy yet, see #5006. Everything else the Axon Framework 4 saga fixture offered works."
        );
    }
}
