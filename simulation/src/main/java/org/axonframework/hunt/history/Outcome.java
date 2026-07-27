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

package org.axonframework.hunt.history;

/**
 * What a history says about an operation's effect.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public enum Outcome {

    /**
     * The operation definitely took effect.
     */
    OK,

    /**
     * The operation definitely did not take effect.
     */
    FAIL,

    /**
     * The operation may or may not have taken effect. Produced by an indeterminate completion and by an invocation
     * that was still in flight when the run ended. A checker must treat both as could-have-succeeded; treating them
     * as failures is how a history-checked suite manufactures findings that are not there.
     */
    UNKNOWN
}
