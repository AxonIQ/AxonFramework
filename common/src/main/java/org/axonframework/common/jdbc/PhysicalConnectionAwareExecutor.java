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
package org.axonframework.common.jdbc;

import org.axonframework.common.annotation.Internal;

import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * A {@link ConnectionExecutor} for a {@link Connection} that is {@link PhysicalConnectionAware}.
 * <p>
 * Where a plain {@link ConnectionExecutor} that has not been used yet leads callers that prefer an independent
 * connection to take one, this executor does so only for as long as its connection holds no physical connection. As
 * soon as it does, for example because application code ran a statement on it, it becomes active and readers use it.
 * That way a unit of work never needs a second connection while it holds one, and can read in parallel for as long as
 * it does not.
 *
 * @author John Hendrikx
 * @since 5.5.0
 */
@Internal
public class PhysicalConnectionAwareExecutor extends ConnectionExecutor {

    private final PhysicalConnectionAware awareConnection;

    /**
     * Constructs a new instance.
     *
     * @param connection the connection of the unit of work, which must be {@link PhysicalConnectionAware}, cannot be
     *                   {@code null}
     * @throws NullPointerException     if {@code connection} is {@code null}
     * @throws IllegalArgumentException if {@code connection} is not {@link PhysicalConnectionAware}
     */
    public PhysicalConnectionAwareExecutor(Connection connection) {
        super(() -> connection);

        Objects.requireNonNull(connection, "The connection cannot be null.");

        if (!(connection instanceof PhysicalConnectionAware aware)) {
            throw new IllegalArgumentException("The connection must be a PhysicalConnectionAware");
        }

        this.awareConnection = aware;
    }

    @Override
    public <R> R withMaterializationShielded(Function<Boolean, R> decision) {
        Objects.requireNonNull(decision, "The decision cannot be null.");

        while (true) {
            if (awareConnection.isPhysicallyConnected()) {

                /*
                 * Someone holds the physical connection, so readers must use it: make this executor active first.
                 * This must happen outside of the shield, as activating takes the lock that the shield holds for
                 * reading.
                 */

                activate();
            }

            Optional<R> result = super.withMaterializationShielded(
                active -> active
                    ? Optional.of(decision.apply(true))
                    : awareConnection.withPhysicalConnectionShielded(
                        physical -> physical ? Optional.<R>empty() : Optional.of(decision.apply(false))
                    )
            );

            if (result.isPresent()) {
                return result.get();
            }

            /*
             * A physical connection was taken after the first check but before the decision was made; go around to
             * activate this executor and decide again.
             */
        }
    }

    private void activate() {
        try {
            apply(connection -> null).join();
        }
        catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw e;
        }
    }
}
