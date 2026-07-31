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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observes whether the framework refuses a message because it cannot resolve a served tenant for it.
 * <p>
 * The command and query sides resolve tenants against the same known tenants, so a refusal looks identical
 * whichever gateway raised it, and an unknown tenant, a removed tenant and a message naming no tenant at all are
 * all refused the same way.
 *
 * @author Laura Devriendt
 * @since 5.3.0
 */
public final class TenantRejections {

    private static final Logger logger = LoggerFactory.getLogger(TenantRejections.class);

    private TenantRejections() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Sends the given {@code dispatch}, reports whether it was refused for an unresolved tenant, and returns that.
     *
     * @param description names what was dispatched, so the log says which refusal was observed
     * @param dispatch    the dispatch expected to be refused
     * @return whether the dispatch was refused for an unresolved tenant
     */
    public static boolean observe(String description, Runnable dispatch) {
        boolean rejected = isRejected(description, dispatch);
        logger.info("The {} was rejected: {}", description, rejected);
        return rejected;
    }

    /**
     * Sends the given {@code dispatch} and returns whether it was refused for an unresolved tenant, without
     * reporting the outcome. A failure for any other reason is logged instead, since it means something other than
     * the tenant being refused.
     * <p>
     * Suits a caller that polls, where a tenant only stops being served after a moment.
     *
     * @param description names what was dispatched, so a failure says which dispatch it belonged to
     * @param dispatch    the dispatch expected to be refused
     * @return whether the dispatch was refused for an unresolved tenant
     */
    public static boolean isRejected(String description, Runnable dispatch) {
        try {
            dispatch.run();
            return false;
        } catch (RuntimeException failure) {
            if (RemoteExceptions.causedBy(failure, TenantNotResolvedException.class)) {
                return true;
            }
            logger.warn("The {} failed, but not because its tenant could not be resolved.", description, failure);
            return false;
        }
    }
}
