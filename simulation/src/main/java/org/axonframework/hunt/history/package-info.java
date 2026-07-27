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
 * Operation-history recording and replay for the Axon Hunt suite.
 * <p>
 * A hunt run records every operation it performs as JSON Lines: one record when the operation is invoked and a
 * separate record when it completes. Checkers never read those files directly; they consume a
 * {@link org.axonframework.hunt.history.HistoryView}, which pairs invocations with completions and exposes
 * still-in-flight operations as explicit unknowns.
 */
@NullMarked
package org.axonframework.hunt.history;

import org.jspecify.annotations.NullMarked;
