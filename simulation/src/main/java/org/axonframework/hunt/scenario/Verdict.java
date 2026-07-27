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

package org.axonframework.hunt.scenario;

/**
 * What a run concluded.
 * <p>
 * Three values, not two. A distributed system returns three outcomes and so does a test of one: it held, it broke, or
 * the run could not tell. Collapsing the third into either of the others is how a suite starts reporting confidence
 * it has not earned.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public enum Verdict {

    /**
     * Every oracle the scenario required ran, every fault it declared landed, the system reached quiescence, and
     * nothing was found broken.
     */
    PASS,

    /**
     * At least one invariant was found broken.
     */
    FAIL,

    /**
     * Nothing was found broken, and the run cannot be reported as a pass: a declared fault never fired, an operation's
     * outcome is unknown, the read side never caught up, a required oracle is not registered, or the run outlived its
     * budget.
     */
    INCONCLUSIVE
}
