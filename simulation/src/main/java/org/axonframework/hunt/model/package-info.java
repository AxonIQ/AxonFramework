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
 * An executable reference model of the Dynamic Consistency Boundary event store.
 * <p>
 * {@link org.axonframework.hunt.model.DcbStoreModel} answers, for any point in a history, whether an append was legal
 * and what the store contains. It is pure, deterministic and depends on the JDK only, so it can be compared against
 * any storage engine and against a TLA+ specification of the same rules without either dragging the other's
 * dependencies along.
 */
@NullMarked
package org.axonframework.hunt.model;

import org.jspecify.annotations.NullMarked;
