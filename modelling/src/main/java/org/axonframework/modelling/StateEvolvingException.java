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

package org.axonframework.modelling;

/**
 * Exception thrown when an error occurs while evolving an entity with an event.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public class StateEvolvingException extends RuntimeException {

    /**
     * Initialize the exception with the given {@code message}.
     *
     * @param message The message describing the exception.
     */
    public StateEvolvingException(String message) {
        super(message);
    }

    /**
     * Initialize the exception with the given {@code message} and {@code cause}.
     *
     * @param message The message describing the exception.
     * @param cause   The underlying cause of the exception.
     */
    public StateEvolvingException(String message, Throwable cause) {
        super(message, cause);
    }
}