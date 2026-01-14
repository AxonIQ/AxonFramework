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

package org.axonframework.extension.micronaut.util.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.axonframework.common.jpa.EntityManagerProvider;

import jakarta.annotation.Nonnull;

/**
 * EntityManagerProvider implementation that expects the container to inject the default container managed
 * EntityManager
 * instance. This implementation expects the container to have exactly one PersistenceUnit, or provide a default if no
 * explicit Persistence Unit Name is provided.
 *
 * @author Allard Buijze
 * @see PersistenceContext
 * @see EntityManager
 * @since 1.3
 */
public class ContainerManagedEntityManagerProvider implements EntityManagerProvider {

    private EntityManager entityManager;

    @Nonnull
    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    /**
     * Sets the container managed entityManager to use. Is generally injected by the application container.
     *
     * @param entityManager the entityManager to return upon request.
     */
    @PersistenceContext
    public void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }
}
