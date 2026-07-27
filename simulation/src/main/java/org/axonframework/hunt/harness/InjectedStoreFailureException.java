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
 * The failure a fault injects into the store, kept distinct from a consistency conflict.
 * <p>
 * The distinction is load-bearing. A conflict is the store's protocol saying no, and the reference model must agree
 * with it. An injected failure is the harness saying no, and the model knows nothing about it. Reporting the two under
 * one error would make every faulted run look like a protocol violation.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class InjectedStoreFailureException extends RuntimeException {

    /**
     * Creates the failure.
     *
     * @param message what the fault refused to do, and why
     */
    public InjectedStoreFailureException(String message) {
        super(message);
    }
}
