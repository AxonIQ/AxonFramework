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

package org.axonframework.extension.micronaut.config.annotation;

import io.micronaut.transaction.annotation.Transactional;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.EventMessage;

/**
 * @author Allard Buijze
 */
@Transactional
public class TransactionalListener implements SomeMeaninglessInterface {

    private int invocations;

    @EventHandler
    public void handleEvent(EventMessage event) {
        this.invocations++;
    }

    public int getInvocations() {
        return invocations;
    }
}
