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

package org.axonframework.messaging.core.unitofwork;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.Context.ResourceKey;

/**
 * Keys of the resources that a {@link UnitOfWork} registers in its {@link ProcessingContext}, for the
 * {@code ProcessingContext} itself to expose.
 * <p>
 * Internal, as users obtain these values through methods of the {@code ProcessingContext}, such as
 * {@link ProcessingContext#requiresSameThreadInvocations()}.
 *
 * @author John Hendrikx
 * @since 5.5.0
 */
@Internal
final class ProcessingContextResources {

    /**
     * Present, with the value {@code true}, when the unit of work requires that all its work is done by the calling
     * thread.
     */
    static final ResourceKey<Boolean> SAME_THREAD_INVOCATIONS = ResourceKey.withLabel("sameThreadInvocations");

    private ProcessingContextResources() {
        // utility class
    }
}
