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

package org.axonframework.modelling.saga.repository;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.SagaLifecycle;

import static org.axonframework.modelling.saga.SagaLifecycle.associateWith;
import static org.axonframework.modelling.saga.SagaLifecycle.removeAssociationWith;

/**
* @author Allard Buijze
*/
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class StubSaga {

    public void registerAssociationValue(AssociationValue associationValue) {
        associateWith(associationValue);
    }

    public void removeAssociationValue(String key, String value) {
        removeAssociationWith(key, value);
    }

    public void end() {
        SagaLifecycle.end();
    }

    public void associate(String key, String value) {
        associateWith(key, value);
    }
}
