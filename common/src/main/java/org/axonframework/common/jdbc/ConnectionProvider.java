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
import java.sql.SQLException;

/**
 * Interface towards a mechanism that provides access to a JDBC {@link Connection}.
 * <p>
 * The returned connection's transactional lifecycle (commit, rollback, and close) is never
 * managed by {@link ConnectionExecutor} or any other consumer of this interface; it remains
 * entirely the responsibility of whatever owns the connection on the other end of this provider,
 * such as a container's transaction manager binding it to the current unit of work.
 *
 * @author Allard Buijze
 * @author John Hendrikx
 * @since 2.2
 */
@FunctionalInterface
public interface ConnectionProvider {

    /**
     * Returns a connection, ready for use.
     * <p>
     * The caller must never commit, roll back, or close the returned connection directly; its
     * lifecycle is managed externally by whatever this provider obtained it from.
     *
     * @return a connection to use, never {@code null}
     * @throws SQLException when an error occurs obtaining the connection
     */
    Connection getConnection() throws SQLException;
}
