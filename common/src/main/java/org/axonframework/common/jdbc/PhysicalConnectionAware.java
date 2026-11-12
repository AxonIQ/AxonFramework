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

import java.sql.Connection;
import java.util.function.Function;

/**
 * Optionally implemented by a {@link Connection} that is handed out lazily, meaning that it only takes a physical
 * connection from its pool when it is first used. It allows {@link PhysicalConnectionAwareExecutor} to tell whether
 * that has happened yet.
 * <p>
 * This is what makes it safe to read in parallel in a unit of work, using other connections, for as long as the
 * connection of the unit of work does not hold a physical connection yet. Once it does, reads must use it, as
 * taking yet another connection from a small pool can deadlock.
 *
 * @author John Hendrikx
 * @since 5.5.0
 */
public interface PhysicalConnectionAware {

    /**
     * Returns whether a physical connection was taken from the pool for this connection.
     *
     * @return {@code true} if a physical connection is held, otherwise {@code false}
     */
    boolean isPhysicallyConnected();

    /**
     * Runs {@code decision}, passing whether a physical connection is held, while preventing a physical connection
     * from being taken for this connection for the duration. A decision that is told {@code false} can therefore rely
     * on it until it returns; anyone who needs a physical connection in the meantime waits for the decision to
     * finish.
     * <p>
     * The decision must not use this connection, as that would need the physical connection that is being held off.
     *
     * @param decision the decision to run, given whether a physical connection is held, cannot be {@code null}
     * @param <R>      the type of the result of {@code decision}
     * @return the result of {@code decision}
     * @throws NullPointerException if {@code decision} is {@code null}
     */
    <R> R withPhysicalConnectionShielded(Function<Boolean, R> decision);
}
