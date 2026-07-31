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

package org.axonframework.examples.demo.multitenancy.shared.messaging;

import io.axoniq.framework.messaging.multitenancy.api.TenantNotResolvedException;

import org.axonframework.common.ExceptionUtils;

/**
 * Recognizes a failure by type, whether it reaches the caller as itself or crosses Axon Server first. In memory
 * the exception travels as itself, so the type is matched directly. Over Axon Server the failure is
 * reconstructed as a generic execution exception that only carries the original type and message as text, so the
 * type's simple name is matched in the message as well.
 * <p>
 * That covers both a failure a handler raised and one the framework raised before a handler was reached, which is
 * how a tenant it cannot resolve is refused.
 *
 * @author Laura Devriendt
 * @since 5.3.0
 */
public final class RemoteExceptions {

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
    public static boolean causedBy(Throwable throwable, Class<? extends Throwable> type) {
        String exceptionName = type.getSimpleName();
        return ExceptionUtils.findException(throwable, cause -> type.isInstance(cause)
                || cause.getClass().getSimpleName().equals(exceptionName)
                || (cause.getMessage() != null && cause.getMessage().contains(exceptionName)))
                             .isPresent();
    }

    /**
     * Returns {@code true} if the given {@code throwable} was caused by a {@link TenantNotResolvedException}, the
     * failure the framework raises when it cannot resolve a served tenant for a message.
     *
     * @param throwable the throwable to inspect
     * @return {@code true} if a {@link TenantNotResolvedException} is in its cause chain
     */
    public static boolean causedByTenantNotResolved(Throwable throwable) {
        return causedBy(throwable, TenantNotResolvedException.class);
    }

    /**
     * Returns {@code true} if the given {@code throwable} indicates a tenant that is not ready for messages yet, so
     * a caller adding a tenant at runtime can retry until it is. That is either its tenant not being resolved, or
     * its Axon Server context still propagating so a message routed to it is briefly rejected as an unknown
     * context. Both are transient while a runtime-added tenant spins up.
     *
     * @param throwable the throwable to inspect
     * @return {@code true} if the failure is a transient not-ready-yet condition
     */
    public static boolean causedByTenantNotReady(Throwable throwable) {
        return causedByTenantNotResolved(throwable) || causedByUnknownContext(throwable);
    }

    // The tenant's Axon Server context is created before the routing to it is in place, so a message sent in that
    // window comes back as an "Unknown Context" failure. Matched by message, as it crosses the wire as a generic
    // execution exception carrying only the original text.
    private static boolean causedByUnknownContext(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("Unknown Context")) {
                return true;
            }
        }
        return false;
    }
}
