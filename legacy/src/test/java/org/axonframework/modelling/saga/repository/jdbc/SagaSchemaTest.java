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

package org.axonframework.modelling.saga.repository.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SagaSchemaTest {

    @Test
    void defaultNamesAreCorrect() {
        SagaSchema sagaSchema = SagaSchema.builder()
            .build();

        assertEquals("SagaEntry", sagaSchema.sagaEntryTable());
        assertEquals("revision", sagaSchema.revisionColumn());
        assertEquals("serializedSaga", sagaSchema.serializedSagaColumn());
        assertEquals("AssociationValueEntry", sagaSchema.associationValueEntryTable());
        assertEquals("associationKey", sagaSchema.associationKeyColumn());
        assertEquals("associationValue", sagaSchema.associationValueColumn());
        assertEquals("sagaId", sagaSchema.sagaIdColumn());
        assertEquals("sagaType", sagaSchema.sagaTypeColumn());
    }

    @Test
    void modifiedNamesAreCorrect() {
        SagaSchema sagaSchema = SagaSchema.builder()
            .sagaEntryTable("SagaEntryModified")
            .revisionColumn("revisionModified")
            .serializedSagaColumn("serializedSagaModified")
            .associationValueEntryTable("AssociationValueEntryModified")
            .associationKeyColumn("associationKeyModified")
            .associationValueColumn("associationValueModified")
            .sagaIdColumn("sagaIdModified")
            .sagaTypeColumn("sagaTypeModified")
            .build();

        assertEquals("SagaEntryModified", sagaSchema.sagaEntryTable());
        assertEquals("revisionModified", sagaSchema.revisionColumn());
        assertEquals("serializedSagaModified", sagaSchema.serializedSagaColumn());
        assertEquals("AssociationValueEntryModified", sagaSchema.associationValueEntryTable());
        assertEquals("associationKeyModified", sagaSchema.associationKeyColumn());
        assertEquals("associationValueModified", sagaSchema.associationValueColumn());
        assertEquals("sagaIdModified", sagaSchema.sagaIdColumn());
        assertEquals("sagaTypeModified", sagaSchema.sagaTypeColumn());
    }

}