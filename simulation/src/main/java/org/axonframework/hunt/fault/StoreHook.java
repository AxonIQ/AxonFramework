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

package org.axonframework.hunt.fault;

/**
 * The seam a fault installs into the store wrapper.
 * <p>
 * The wrapper calls every installed hook around each append and each commit. A hook may delay the call, and it may
 * ask for the commit to be interfered with by returning something other than {@link CommitAction#proceed()}. It never
 * touches the store itself, so a fault stays a description of what should go wrong rather than an implementation of
 * how.
 * <p>
 * Hooks are called from whichever workload thread issued the append, so implementations must be thread-safe.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public interface StoreHook {

    /**
     * Called before the append reaches the store.
     * <p>
     * The default does nothing. A latency fault sleeps here; a fault that wants the append never to be attempted
     * throws from here.
     *
     * @param attempt what is about to be appended
     */
    default void beforeAppend(AppendAttempt attempt) {
        // No interference by default.
    }

    /**
     * Called when the append transaction is about to commit.
     *
     * @param attempt what is about to be committed
     * @return what the wrapper should do with the batch; {@link CommitAction#proceed()} by default
     */
    default CommitAction onCommit(AppendAttempt attempt) {
        return CommitAction.proceed();
    }
}
