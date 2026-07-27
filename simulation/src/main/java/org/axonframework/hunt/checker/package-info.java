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
 * The oracles a hunt run is judged by.
 * <p>
 * A {@link org.axonframework.hunt.checker.Checker} reads a
 * {@link org.axonframework.hunt.history.HistoryView} and reports whether the invariants it enforces held. Checkers
 * are found through the {@link java.util.ServiceLoader}, so adding one means adding a class and a service entry, and
 * editing nothing that already exists.
 */
@NullMarked
package org.axonframework.hunt.checker;

import org.jspecify.annotations.NullMarked;
