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
 * The load a hunt run puts on the framework, and the shape that load takes.
 * <p>
 * There is one canonical workload, {@link org.axonframework.hunt.workload.LedgerWorkload}, because a conservation law
 * is the cheapest strong oracle there is: money is neither created nor destroyed, so one arithmetic identity catches
 * a lost event, a doubled event, a bypassed conflict check and a torn batch without knowing which of them broke.
 * <p>
 * Contention is a seeded dimension rather than a constant. {@link org.axonframework.hunt.workload.SwarmShape} derives
 * the writer count, the tag cardinality, the access distribution, the overlap between writers and the batch size from
 * one seed, because state-space coverage comes from the shape of the load and not from its volume.
 */
@NullMarked
package org.axonframework.hunt.workload;

import org.jspecify.annotations.NullMarked;
