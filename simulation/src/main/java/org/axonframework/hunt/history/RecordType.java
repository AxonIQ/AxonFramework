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
 * The four kinds of {@link HistoryRecord} a hunt run may emit.
 * <p>
 * The set is closed on purpose: it mirrors the invoke / ok / fail / info alphabet that history-based checkers are
 * built on. A real distributed system returns three outcomes, not two, and {@link #INFO} is the third one: the
 * operation may or may not have taken effect.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public enum RecordType {

    /**
     * The operation was issued. Always paired with exactly one later completion record carrying the same correlation
     * identifier, unless the run ended before the operation completed.
     */
    INVOKE,

    /**
     * The operation completed successfully.
     */
    OK,

    /**
     * The operation completed with a definite failure: the caller knows it did not take effect.
     */
    FAIL,

    /**
     * The operation's outcome is indeterminate: it may or may not have taken effect. Emitted for timeouts, dropped
     * connections, and any other completion that cannot distinguish "did not happen" from "happened but the
     * acknowledgement was lost". Also used for standalone notes such as post-run store scans and fault-landing
     * evidence, which carry no invocation.
     */
    INFO
}
