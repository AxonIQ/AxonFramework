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

package org.axonframework.common.jpa;

import jakarta.persistence.EntityManager;

/**
 * Provides components with an {@link EntityManager} to access the persistence mechanism. Depending on
 * the application environment, this may be a single container managed EntityManager, or an application
 * managed instance for one-time use.
 * <p>
 * The returned entity manager's transactional lifecycle (commit, rollback, and close) is never
 * managed by {@link EntityManagerExecutor} or any other consumer of this interface; it remains entirely
 * the responsibility of whatever owns the entity manager on the other end of this provider, such as a
 * container's transaction manager binding it to the current unit of work.
 *
 * @author Allard Buijze
 * @author John Hendrikx
 * @since 1.3
 */
public interface EntityManagerProvider {

    /**
     * Returns the {@link EntityManager} instance to use.
     * <p>
     * The caller must never commit, roll back, or close the returned entity manager directly; its
     * lifecycle is managed externally by whatever this provider obtained it from.
     *
     * @return the entity manager instance to use, never {@code null}
     */
    EntityManager getEntityManager();
}
