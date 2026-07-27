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

package org.axonframework.hunt.harness;

/**
 * How much of the run's scheduling the harness pins down.
 * <p>
 * Neither mode is the honest default for every question, which is why both exist and why what each one buys is
 * measured by the determinism probe rather than asserted here.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public enum DeterminismMode {

    /**
     * Real thread pools everywhere: as many writer threads as the workload's shape asks for, and multi-threaded
     * coordinator and worker executors on the streaming processor.
     * <p>
     * This is the mode that finds interleaving bugs, and it is the default for that reason. A seed fixes the shape of
     * the load and nothing about the schedule.
     */
    REAL_THREADS,

    /**
     * One writer, and single-threaded coordinator and worker executors.
     * <p>
     * The framework's own executor injection points are used, so nothing about the engine changes. What this removes
     * is concurrency between writers and between processor workers; what it does not remove is the interleaving
     * between the workload thread and the processor threads, which remain distinct threads because the streaming
     * processor's coordinator must run while the workload writes.
     */
    SINGLE_THREADED
}
