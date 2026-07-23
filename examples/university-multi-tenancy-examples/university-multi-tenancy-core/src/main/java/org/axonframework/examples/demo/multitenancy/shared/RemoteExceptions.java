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

package org.axonframework.examples.demo.multitenancy.shared;

/**
 * Recognizes a failure that a handler raised, whether it reaches the caller as itself or crosses Axon
 * Server first. In memory the exception travels as itself, so the type is matched directly. Over Axon
 * Server the failure crosses the wire and is reconstructed as a generic execution exception that only
 * carries the original type and message as text, so the type's simple name is matched in the message as
 * well.
 */
final class RemoteExceptions {

    private RemoteExceptions() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Returns {@code true} if the given {@code throwable} was caused by an exception of the given
     * {@code type}, matching it directly or, for a failure reconstructed over Axon Server, by its simple
     * name in the exception type or message.
     *
     * @param throwable the throwable to inspect
     * @param type      the exception type to look for in the cause chain
     * @return {@code true} if the type is in the cause chain, directly or by name
     */
    static boolean causedBy(Throwable throwable, Class<? extends Throwable> type) {
        String exceptionName = type.getSimpleName();
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause) || cause.getClass().getSimpleName().equals(exceptionName)) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && message.contains(exceptionName)) {
                return true;
            }
        }
        return false;
    }
}
