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
 * Association values, the means by which an event is correlated to the saga instances that should handle it.
 * <p>
 * An {@link org.axonframework.modelling.saga.AssociationValue} is a key-value pair that a saga registers itself under;
 * {@link org.axonframework.modelling.saga.AssociationValues} tracks a saga's current set together with the additions
 * and removals made since it was last stored, which is what a
 * {@link org.axonframework.modelling.saga.repository.SagaStore} persists.
 * <p>
 * These types carry the Axon Framework 4 API unchanged, to ease migration of projects that cannot move off it in one
 * go.
 */
@NullMarked
package org.axonframework.modelling.saga;

import org.jspecify.annotations.NullMarked;
