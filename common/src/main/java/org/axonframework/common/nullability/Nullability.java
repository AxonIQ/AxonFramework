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

package org.axonframework.common.nullability;

import org.axonframework.common.annotation.Internal;

import java.lang.reflect.Parameter;

/**
 * The declared nullability of a {@link Parameter}, as reported by a {@link NullabilityResolver}.
 * <p>
 * This is deliberately three-valued. Absence of a {@code Nullable} annotation is not evidence that a parameter is
 * non-null, since most Java code carries no nullability annotations at all. A resolver that cannot form an opinion
 * therefore reports {@link #UNKNOWN} rather than {@link #NON_NULL}, allowing lower-priority resolvers to answer and
 * letting the caller apply its own default.
 * <p>
 * Marked {@link Internal} as it forms part of the {@link NullabilityResolver} contract.
 *
 * @author Mateusz Nowak
 * @see NullabilityResolver
 * @since 5.3.0
 */
@Internal
public enum Nullability {

    /**
     * The parameter is explicitly declared as accepting {@code null}.
     */
    NULLABLE,

    /**
     * The parameter is explicitly declared as not accepting {@code null}.
     */
    NON_NULL,

    /**
     * No nullability could be determined for the parameter.
     */
    UNKNOWN
}
