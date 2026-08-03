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

/**
 * Resolution of the nullability declared for a method or constructor parameter.
 * <p>
 * Annotations are only one way a language expresses nullability, which is why this lives beside rather than inside
 * the annotation utilities. Kotlin, for one, encodes it in its type system. The
 * {@link org.axonframework.common.nullability.NullabilityResolver} service interface lets such languages contribute
 * resolution the framework cannot perform on its own.
 */
@NullMarked
package org.axonframework.common.nullability;

import org.jspecify.annotations.NullMarked;
