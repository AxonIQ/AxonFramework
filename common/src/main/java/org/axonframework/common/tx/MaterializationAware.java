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

package org.axonframework.common.tx;

import org.axonframework.common.annotation.Internal;

import java.util.function.Function;

/**
 * Optionally implemented by a {@link TransactionalExecutor} that lazily materializes its resource, so
 * {@link TransactionalExecutorProvider#applyPreferringIndependent} can prefer an independently-managed
 * resource over forcing this one to materialize - reusing it instead when it's already active.
 * <p>
 * Deliberately kept off {@link TransactionalExecutor} itself: materialization awareness is a
 * provider-orchestration concern, not an operation a consumer of a {@link TransactionalExecutor} should
 * ever need - consumers only need {@code accept}/{@code apply}.
 *
 * @author John Hendrikx
 * @since 5.4.0
 */
@Internal
public interface MaterializationAware {

    /**
     * Runs {@code decision}, passing whether this executor already has a resource materialized, while
     * preventing a concurrent {@link TransactionalExecutor#apply} call from materializing it for the
     * duration - so a caller can fall back to an independent executor without racing a concurrent
     * materialization.
     *
     * @param <R>      the type of the result produced by {@code decision}
     * @param decision given {@code true} if this executor already has a resource materialized, otherwise
     *                 {@code false}; run shielded from a concurrent materialization, cannot be {@code null}
     * @return the result of {@code decision}
     * @throws NullPointerException when {@code decision} is {@code null}
     */
    <R> R withMaterializationShielded(Function<Boolean, R> decision);
}
