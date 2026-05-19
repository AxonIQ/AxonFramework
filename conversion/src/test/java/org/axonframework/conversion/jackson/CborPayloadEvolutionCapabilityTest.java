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

package org.axonframework.conversion.jackson;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.cbor.CBORMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies what AF5 handles automatically at payload conversion time when using CBOR serialization -- no upcaster
 * needed.
 * <p>
 * Each nested class maps to one row in the capability map (Part A of the upcasting spec). Tests use
 * {@link JacksonConverter} backed by a {@link CBORMapper}, which is the CBOR serialization configuration.
 * <p>
 * CBOR (Concise Binary Object Representation) is a binary encoding format, but it uses Jackson's data model
 * identically to JSON. The same annotations ({@code @JsonIgnoreProperties}, {@code @JsonProperty}) and the same
 * numeric coercion rules apply. The only difference from JSON is the stored byte representation -- CBOR bytes are
 * compact binary, not human-readable text.
 * <p>
 * Note: CBOR annotations remain in the {@code com.fasterxml.jackson.annotation} package even when using the
 * Jackson 3 ({@code tools.jackson}) CBORMapper, as confirmed by the Jackson 3 project ("Annotations remain at
 * Jackson 2.x group id").
 */
class CborPayloadEvolutionCapabilityTest {

    private static final ObjectMapper CBOR_MAPPER = CBORMapper.builder().findAndAddModules().build();

    // Strict CBORMapper: fails on unknown properties unless @JsonIgnoreProperties opts out.
    // This makes the annotation the mechanism under test, not a Jackson default.
    private static final ObjectMapper CBOR_MAPPER_STRICT = CBORMapper.builder()
                                                                      .findAndAddModules()
                                                                      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                                                      .build();

    // The converter under test: JacksonConverter backed by CBORMapper
    private final JacksonConverter cborConverter = new JacksonConverter(CBOR_MAPPER);
    private final JacksonConverter strictCborConverter = new JacksonConverter(CBOR_MAPPER_STRICT);

    // -------------------------------------------------------------------------
    // Scenario 1 -- Added a new field
    //
    // A new optional field is added to the event class.
    // Old events stored in CBOR without this field receive null when deserialized.
    // -------------------------------------------------------------------------

    @Nested
    class AddedNewField {

        record OldCourseCreated(String name, int capacity) {}

        record CourseCreatedWithDescription(String name, int capacity, String description) {}

        @Test
        void newFieldDefaultsToNull_whenMissingFromStoredCborPayload() {
            // given -- event stored before `description` was added; CBOR bytes of old record
            byte[] storedPayload = cborConverter.convert(new OldCourseCreated("Mathematics", 30), byte[].class);

            // when -- JacksonConverter(CBORMapper) converts stored CBOR bytes to the new class
            CourseCreatedWithDescription result =
                    cborConverter.convert(storedPayload, CourseCreatedWithDescription.class);

            // then -- existing fields preserved; new `description` defaults to null
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("Mathematics");
            assertThat(result.capacity()).isEqualTo(30);
            assertThat(result.description()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 2 -- Removed a field
    //
    // A field is removed from the event class.
    // Old stored CBOR events still carry the field -- @JsonIgnoreProperties drops it.
    // -------------------------------------------------------------------------

    @Nested
    class RemovedField {

        record StudentEnrolledOld(String facultyId, String studentId, String firstName, String lastName) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record StudentEnrolledWithoutLastName(String facultyId, String studentId, String firstName) {}

        record StudentEnrolledWithoutAnnotation(String facultyId, String studentId, String firstName) {}

        @Test
        void removedField_isIgnoredWhenPresentInStoredCborPayload() {
            // given -- CBOR bytes of old record that still has `lastName`; use strict converter to prove annotation is the mechanism
            byte[] storedPayload = strictCborConverter.convert(
                    new StudentEnrolledOld("fac-1", "stu-1", "John", "Doe"), byte[].class
            );

            // when -- @JsonIgnoreProperties(ignoreUnknown=true) opts out of strict mode; `lastName` is dropped
            StudentEnrolledWithoutLastName result =
                    strictCborConverter.convert(storedPayload, StudentEnrolledWithoutLastName.class);

            // then -- known fields preserved; removed `lastName` dropped without error
            assertThat(result).isNotNull();
            assertThat(result.firstName()).isEqualTo("John");
        }

        @Test
        void removedField_failsWithoutAnnotation_whenConverterIsStrict() {
            // given -- same stored CBOR bytes; strict converter; class has no @JsonIgnoreProperties opt-out
            byte[] storedPayload = strictCborConverter.convert(
                    new StudentEnrolledOld("fac-1", "stu-1", "John", "Doe"), byte[].class
            );

            // when / then -- strict CBORMapper rejects `lastName`; annotation is what prevents failure
            assertThatThrownBy(() -> strictCborConverter.convert(storedPayload, StudentEnrolledWithoutAnnotation.class))
                    .isInstanceOf(Exception.class);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 3 -- Renamed a field
    //
    // A field is renamed in the event class.
    // @JsonProperty maps the stored CBOR key to the new field name.
    // -------------------------------------------------------------------------

    @Nested
    class RenamedField {

        record CourseCreatedOld(String name, int capacity) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record CourseCreatedWithRenamedCapacity(String name, @JsonProperty("capacity") int maxCapacity) {}

        @Test
        void renamedField_isMappedViaJsonProperty() {
            // given -- CBOR bytes stored with the old field name `capacity`
            byte[] storedPayload = cborConverter.convert(new CourseCreatedOld("Mathematics", 50), byte[].class);

            // when -- JacksonConverter(CBORMapper) maps `capacity` to `maxCapacity` via @JsonProperty
            CourseCreatedWithRenamedCapacity result =
                    cborConverter.convert(storedPayload, CourseCreatedWithRenamedCapacity.class);

            // then -- value correctly mapped to the new field name
            assertThat(result).isNotNull();
            assertThat(result.maxCapacity()).isEqualTo(50);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 4 -- Changed a field to a compatible type
    //
    // A field's type is widened (e.g., int -> long).
    // CBORMapper (Jackson) coerces compatible numeric types automatically.
    // -------------------------------------------------------------------------

    @Nested
    class CompatibleTypeChange {

        record CourseCreatedIntCapacity(String name, int capacity) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record CourseCreatedLongCapacity(String name, long capacity) {}

        @Test
        void intWidenedToLong_isCoercedByCborConverter() {
            // given -- CBOR bytes stored with int capacity
            byte[] storedPayload = cborConverter.convert(new CourseCreatedIntCapacity("Mathematics", 30), byte[].class);

            // when -- CBORMapper coerces int to long during deserialization
            CourseCreatedLongCapacity result =
                    cborConverter.convert(storedPayload, CourseCreatedLongCapacity.class);

            // then -- numeric type widening handled transparently
            assertThat(result).isNotNull();
            assertThat(result.capacity()).isEqualTo(30L);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 6 -- Handler wants a different representation
    //
    // Two handlers read the same stored CBOR bytes: one requests the typed class,
    // the other requests a JsonNode. CBORMapper can produce JsonNode from CBOR bytes.
    // -------------------------------------------------------------------------

    @Nested
    class HandlerReceivesDifferentRepresentation {

        record CourseCreated(String name, int capacity) {}

        @Test
        void differentHandlers_receiveTheSameStoredCborBytes_inDifferentRepresentations() {
            // given -- one event stored as CBOR bytes
            byte[] storedPayload = cborConverter.convert(new CourseCreated("Mathematics", 30), byte[].class);

            // when -- handler A requests the typed class; handler B requests JsonNode
            CourseCreated typedResult = cborConverter.convert(storedPayload, CourseCreated.class);
            JsonNode jsonResult = cborConverter.convert(storedPayload, JsonNode.class);

            // then -- handler A receives the typed object with correct field values
            assertThat(typedResult).isNotNull();
            assertThat(typedResult.name()).isEqualTo("Mathematics");
            assertThat(typedResult.capacity()).isEqualTo(30);

            // and -- handler B receives the same data as a JsonNode decoded from CBOR bytes
            assertThat(jsonResult).isNotNull();
            assertThat(jsonResult.get("name").stringValue()).isEqualTo("Mathematics");
            assertThat(jsonResult.get("capacity").intValue()).isEqualTo(30);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 7 -- Switched serialization format
    //
    // JacksonConverter backed by CBORMapper is a drop-in for JacksonConverter
    // backed by JsonMapper. Event classes require no changes -- only the
    // CBORMapper configuration changes.
    // -------------------------------------------------------------------------

    @Nested
    class SwitchedSerializationFormat {

        record CourseCreated(String name, int capacity) {}

        @Test
        void eventClass_requiresNoChange_whenSerializationFormatIsCbor() {
            // given -- payload stored as CBOR binary bytes (format switched from, e.g., JSON to CBOR)
            byte[] storedPayload = cborConverter.convert(new CourseCreated("Mathematics", 30), byte[].class);

            // when -- JacksonConverter(CBORMapper) converts CBOR bytes to the event class; only the mapper changed
            CourseCreated result = cborConverter.convert(storedPayload, CourseCreated.class);

            // then -- handler receives correct data; event class unchanged
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("Mathematics");
            assertThat(result.capacity()).isEqualTo(30);
        }
    }
}
