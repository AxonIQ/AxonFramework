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

package org.axonframework.conversion;

import org.axonframework.common.AxonNonTransientException;

/**
 * Indicates that an exception occurred while serializing or deserializing an object.
 *
 * @author Allard Buijze
 * @since 0.6
 * TODO #3602 remove
 * @deprecated By shifting from the {@link Serializer} to the {@link Converter}, this exception becomes obsolete.
 */
@Deprecated(forRemoval = true, since = "5.0.0")
public class SerializationException extends AxonNonTransientException {

    /**
     * Initializes the exception using the given {@code message}.
     *
     * @param message The message describing the exception
     */
    public SerializationException(String message) {
        super(message);
    }

    /**
     * Initializes the exception using the given {@code message} and {@code cause}.
     *
     * @param message The message describing the exception
     * @param cause   The underlying cause of the exception
     */
    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
