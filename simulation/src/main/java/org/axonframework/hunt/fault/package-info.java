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
 * The faults a hunt run injects, and the schedule that phases them.
 * <p>
 * A fault is one class. It declares its kind, installs itself on a {@link org.axonframework.hunt.fault.FaultSite}
 * when its window opens, and removes itself when the window closes. Adding a kind therefore adds a class and edits
 * nothing: the {@link org.axonframework.hunt.fault.FaultSchedule} takes whatever it is handed.
 * <p>
 * Every fault must prove it fired. A fault increments a {@link org.axonframework.hunt.fault.FaultEvidence} counter
 * each time it actually perturbs something, and the runner writes that count into the history. A declared fault with
 * no fires makes the run inconclusive, because a green run under a fault that never landed has verified nothing.
 */
@NullMarked
package org.axonframework.hunt.fault;

import org.jspecify.annotations.NullMarked;
