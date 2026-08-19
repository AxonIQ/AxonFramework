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

package org.axonframework.modelling.entity.child;

import org.axonframework.modelling.entity.child.mock.RecordingChildEntity;
import org.junit.jupiter.api.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FieldChildEntityFieldDefinitionTest {

    @Test
    void canRetrieveChildEntityIfOnlyHasField() {
        RecordingChildEntity childEntity = new RecordingChildEntity("7826736");
        ParentEntityWithOnlyField parentEntity = new ParentEntityWithOnlyField(childEntity);

        FieldChildEntityFieldDefinition<ParentEntityWithOnlyField, RecordingChildEntity> testSubject =
                new FieldChildEntityFieldDefinition<>(ParentEntityWithOnlyField.class, "childEntity");

        assertEquals(childEntity, testSubject.getChildValue(parentEntity));
    }

    @Test
    void canEvolveChildEntityIfOnlyHasField() {
        RecordingChildEntity childEntity = new RecordingChildEntity("7826736");
        ParentEntityWithOnlyField parentEntity = new ParentEntityWithOnlyField(childEntity);

        FieldChildEntityFieldDefinition<ParentEntityWithOnlyField, RecordingChildEntity> testSubject =
                new FieldChildEntityFieldDefinition<>(ParentEntityWithOnlyField.class, "childEntity");

        RecordingChildEntity newChildEntity = new RecordingChildEntity("1234567");
        ParentEntityWithOnlyField evolvedParentEntity = testSubject
                .evolveParentBasedOnChildInput(parentEntity, newChildEntity);

        assertEquals(newChildEntity, evolvedParentEntity.randomNameForRetrievingChildEntityToAvoidGetterLogic());
    }

    @Test
    void canRetrieveChildEntityIfHasGetterAndSetter() {
        RecordingChildEntity childEntity = new RecordingChildEntity("7826736");
        ParentEntityWithGetterAndSetter parentEntity = new ParentEntityWithGetterAndSetter();
        parentEntity.setChildEntity(childEntity);

        FieldChildEntityFieldDefinition<ParentEntityWithGetterAndSetter, RecordingChildEntity> testSubject =
                new FieldChildEntityFieldDefinition<>(ParentEntityWithGetterAndSetter.class, "childEntity");

        assertEquals(childEntity, testSubject.getChildValue(parentEntity));
        assertTrue(parentEntity.getterCalled.get());
    }

    @Test
    void canEvolveChildEntityIfHasGetterAndSetter() {
        RecordingChildEntity childEntity = new RecordingChildEntity("7826736");
        ParentEntityWithGetterAndSetter parentEntity = new ParentEntityWithGetterAndSetter();
        parentEntity.setChildEntity(childEntity);

        FieldChildEntityFieldDefinition<ParentEntityWithGetterAndSetter, RecordingChildEntity> testSubject =
                new FieldChildEntityFieldDefinition<>(ParentEntityWithGetterAndSetter.class, "childEntity");

        RecordingChildEntity newChildEntity = new RecordingChildEntity("1234567");
        ParentEntityWithGetterAndSetter evolvedParentEntity = testSubject
                .evolveParentBasedOnChildInput(parentEntity, newChildEntity);

        assertEquals(newChildEntity, evolvedParentEntity.getChildEntity());
        assertTrue(parentEntity.setterCalled.get());
    }

    @Test
    void canRetrieveChildEntityFromRecord() {
        RecordingChildEntity childEntity = new RecordingChildEntity("7826736");
        ParentEntityWithEvolveMethod parentEntity = new ParentEntityWithEvolveMethod(childEntity, false);

        FieldChildEntityFieldDefinition<ParentEntityWithEvolveMethod, RecordingChildEntity> testSubject =
                new FieldChildEntityFieldDefinition<>(ParentEntityWithEvolveMethod.class, "childEntity");

        assertEquals(childEntity, testSubject.getChildValue(parentEntity));
    }

    @Test
    void canEvolveChildEntityIfHasEvolveMethod() {
        RecordingChildEntity childEntity = new RecordingChildEntity("7826736");
        ParentEntityWithEvolveMethod parentEntity = new ParentEntityWithEvolveMethod(childEntity, false);

        FieldChildEntityFieldDefinition<ParentEntityWithEvolveMethod, RecordingChildEntity> testSubject =
                new FieldChildEntityFieldDefinition<>(ParentEntityWithEvolveMethod.class, "childEntity");

        RecordingChildEntity newChildEntity = new RecordingChildEntity("1234567");
        ParentEntityWithEvolveMethod evolvedParentEntity = testSubject
                .evolveParentBasedOnChildInput(parentEntity, newChildEntity);

        assertEquals(newChildEntity, evolvedParentEntity.childEntity());
        assertTrue(evolvedParentEntity.evolved);
    }

    @Test
    void fallsBackToFieldWhenSameNamedAccessorHasIncompatibleReturnType() {
        ParentEntityWithIncompatibleGetter parentEntity = new ParentEntityWithIncompatibleGetter();

        FieldChildEntityFieldDefinition<ParentEntityWithIncompatibleGetter, RecordingChildEntity> testSubject =
                new FieldChildEntityFieldDefinition<>(ParentEntityWithIncompatibleGetter.class, "childEntity");

        assertNull(testSubject.getChildValue(parentEntity));
    }

    private static class ParentEntityWithOnlyField {
        private final RecordingChildEntity childEntity;

        private ParentEntityWithOnlyField(RecordingChildEntity childEntity) {
            this.childEntity = childEntity;
        }

        public RecordingChildEntity randomNameForRetrievingChildEntityToAvoidGetterLogic() {
            return childEntity;
        }
    }

    private static class ParentEntityWithGetterAndSetter {
        private RecordingChildEntity childEntity;
        private AtomicBoolean getterCalled = new AtomicBoolean(false);
        private AtomicBoolean setterCalled = new AtomicBoolean(false);

        public RecordingChildEntity getChildEntity() {
            getterCalled.set(true);
            return childEntity;
        }

        public void setChildEntity(RecordingChildEntity childEntity) {
            setterCalled.set(true);
            this.childEntity = childEntity;
        }
    }

    private record ParentEntityWithEvolveMethod(
            RecordingChildEntity childEntity,
            boolean evolved
    ) {

        public ParentEntityWithEvolveMethod evolveChildEntity(RecordingChildEntity childEntity) {
            return new ParentEntityWithEvolveMethod(childEntity, true);
        }
    }

    private static class ParentEntityWithIncompatibleGetter {

        private RecordingChildEntity childEntity;

        // Fluent-style, same name as the field, but wraps the value in Optional -- must not be
        // mistaken for a same-typed getter.
        public Optional<RecordingChildEntity> childEntity() {
            return Optional.ofNullable(childEntity);
        }
    }

}