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

package org.axonframework.extension.spring.config.saga.beta;

import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;

/**
 * A Saga sharing its simple name with {@code org.axonframework.extension.spring.config.saga.alpha.SharedNameSaga}.
 * <p>
 * The pair exists to verify that two Saga types deriving the same processor name end up on one processor, and that
 * their component names fall back to the fully qualified class name to stay unique.
 *
 * @author Mateusz Nowak
 */
public class SharedNameSaga {

    /**
     * Starts a Saga instance for the given {@code event}.
     *
     * @param event the event starting this Saga
     */
    @StartSaga
    @SagaEventHandler(associationProperty = "id")
    public void on(SagaStarted event) {
        // Intentionally empty; the Saga only needs a handler to be a valid event handling component.
    }

    /**
     * The event starting a {@code SharedNameSaga}.
     *
     * @param id the association value of the Saga instance
     */
    public record SagaStarted(String id) {

    }
}
