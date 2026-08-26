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

package org.axonframework.deadline;

import org.axonframework.common.AxonTransientException;

/**
 * Exception which occurs during deadline message processing.
 *
 * @author Milan Savic
 * @since 3.3
 */
public class DeadlineException extends AxonTransientException {

    /**
     * Initializes deadline exception with message and no cause.
     *
     * @param message message describing what went wrong
     */
    public DeadlineException(String message) {
        super(message);
    }

    /**
     * Initializes deadline exception with message and actual cause.
     *
     * @param message message describing what went wrong
     * @param cause   actual cause of exception
     */
    public DeadlineException(String message, Throwable cause) {
        super(message, cause);
    }
}
