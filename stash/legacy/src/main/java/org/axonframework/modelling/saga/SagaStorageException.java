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

package org.axonframework.modelling.saga;

import org.axonframework.common.AxonTransientException;

/**
 * Exception indicating that an error has occurred while storing a Saga.
 *
 * @author Allard Buijze
 * @since 0.7
 */
public class SagaStorageException extends AxonTransientException {

    /**
     * Initialize a SagaStorageException with the given descriptive {@code message}.
     *
     * @param message The message describing the error.
     */
    public SagaStorageException(String message) {
        super(message);
    }

    /**
     * Initialize a SagaStorageException with the given descriptive {@code message} and {@code cause}.
     *
     * @param message The message describing the error.
     * @param cause   The cause of the error.
     */
    public SagaStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
