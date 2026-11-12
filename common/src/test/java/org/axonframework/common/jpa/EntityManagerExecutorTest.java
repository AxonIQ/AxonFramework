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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link EntityManagerExecutor}.
 *
 * @author John Hendrikx
 */
class EntityManagerExecutorTest {

    private final EntityManager entityManager = (EntityManager) Proxy.newProxyInstance(
        EntityManager.class.getClassLoader(), new Class<?>[]{EntityManager.class}, (proxy, method, args) -> null
    );

    @Test
    void shouldBeActiveFromTheStartWhenCreatedForAnEntityManager() {
        // given
        EntityManagerExecutor testSubject = new EntityManagerExecutor(entityManager);

        // when
        Boolean active = testSubject.withMaterializationShielded(isActive -> isActive);

        // then
        assertThat(active).isTrue();
    }

    @Test
    void shouldUseTheEntityManagerItWasCreatedFor() {
        // given
        EntityManagerExecutor testSubject = new EntityManagerExecutor(entityManager);

        // when
        EntityManager used = testSubject.apply(em -> em).join();

        // then
        assertThat(used).isSameAs(entityManager);
    }

    @Test
    void shouldNotBeActiveUntilUsedWhenCreatedForAProvider() {
        // given
        EntityManagerExecutor testSubject = new EntityManagerExecutor(() -> entityManager);

        // when
        Boolean activeBefore = testSubject.withMaterializationShielded(isActive -> isActive);

        testSubject.apply(em -> em).join();

        Boolean activeAfter = testSubject.withMaterializationShielded(isActive -> isActive);

        // then
        assertThat(activeBefore).isFalse();
        assertThat(activeAfter).isTrue();
    }

    @Test
    void shouldRejectANullEntityManager() {
        // when / then
        assertThatThrownBy(() -> new EntityManagerExecutor((EntityManager) null))
            .isInstanceOf(NullPointerException.class);
    }
}
