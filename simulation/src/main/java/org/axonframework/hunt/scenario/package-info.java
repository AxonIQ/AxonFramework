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
 * Scenarios as data, and the one runner that executes them.
 * <p>
 * A scenario is a {@link org.axonframework.hunt.scenario.Scenario} record: which claims it tries to falsify, which
 * load it puts on, which faults it injects and when, which store, which timings, which oracles must run, which seed,
 * and what it is allowed to cost. Adding one adds a record. The runner does not learn its name, and no existing
 * scenario is touched.
 * <p>
 * That is the whole design constraint. The scenarios in the plan are the first instances, not the product; the
 * product is the harness they run on, and it earns its keep only if the next twenty scenarios cost nothing but their
 * own declarations.
 */
@NullMarked
package org.axonframework.hunt.scenario;

import org.jspecify.annotations.NullMarked;
