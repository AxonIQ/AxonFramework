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

package org.axonframework.extension.kotlin.eventsourcing

/**
 * Helper function to avoid boilerplate code in event sourcing handlers.
 * Conditionally evolves the current instance.
 *
 * @author Simon Zambrovski
 * @since 5.1.0
 * @param condition A condition to execute the evolution.
 * @param evolver A function to be executed.
 * @return itself or evolved version.
 */
inline fun <T> T.evolveIf(
    condition: Boolean,
    evolver: (T) -> T
): T = if (condition) {
    evolver(this)
} else {
    this
}
