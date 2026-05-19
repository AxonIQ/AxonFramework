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

package org.axonframework.conversion.avro;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.message.SchemaStore;
import org.axonframework.conversion.avro.test.ComplexObject;
import org.axonframework.conversion.avro.test.ComplexObjectSchemas;
import org.axonframework.conversion.avro.test.ComplexObjectWithLongValue3;
import org.axonframework.conversion.avro.test.ComplexObjectWithRenamedValue1;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what AF5 handles automatically at payload conversion time when using Avro serialization -- no upcaster
 * needed.
 * <p>
 * Each nested class maps to one row in the capability map (Part A of the upcasting spec). Tests use the
 * {@link AvroConverter} directly, which is what {@code DelegatingEventConverter} delegates to at handling time.
 * <p>
 * Unlike the Jackson-based scenarios, Avro schema evolution uses writer/reader schema resolution rather than
 * annotations. The writer schema is embedded in the stored binary payload (Avro Binary Message Encoding). The reader
 * schema is the current event class schema. Avro resolves field additions, removals, aliases, and compatible type
 * promotions automatically via its schema resolution rules.
 */
class AvroPayloadEvolutionCapabilityTest {

    private static final GenericRecordToByteArrayConverter TO_BYTES = new GenericRecordToByteArrayConverter();

    // -------------------------------------------------------------------------
    // Scenario 1 -- Added a new field
    //
    // ComplexObject has value2 (with schema default "default value").
    // Old events stored without value2 receive the schema default -- Avro's
    // reader schema default-fills missing fields automatically.
    // -------------------------------------------------------------------------

    @Nested
    class AddedNewField {

        @Test
        void newField_receivesSchemaDefault_whenMissingFromStoredPayload() {
            // given -- writer schema without value2 (simulates event stored before value2 was added)
            Schema writerSchema = ComplexObjectSchemas.compatibleSchemaWithoutValue2;
            SchemaStore.Cache store = new SchemaStore.Cache();
            store.addSchema(ComplexObject.getClassSchema());
            store.addSchema(writerSchema);
            AvroConverter converter = new AvroConverter(store, c -> c);

            GenericData.Record storedRecord = new GenericData.Record(writerSchema);
            storedRecord.put("value1", "hello");
            storedRecord.put("value3", 10);
            byte[] storedBytes = TO_BYTES.convert(storedRecord);

            // when -- AF5 Avro converter reads the stored bytes using the current class schema as reader
            ComplexObject result = converter.convert(storedBytes, ComplexObject.class);

            // then -- existing fields preserved; missing value2 receives the Avro schema default
            assertThat(result).isNotNull();
            assertThat(result.getValue1()).isEqualTo("hello");
            assertThat(result.getValue3()).isEqualTo(10);
            assertThat(result.getValue2()).isEqualTo("default value");
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 2 -- Removed a field
    //
    // ComplexObject drops value4 (an extra field that was later removed).
    // Old events still carry value4 in their binary payload -- Avro silently
    // ignores writer fields that are absent from the reader schema.
    // -------------------------------------------------------------------------

    @Nested
    class RemovedField {

        @Test
        void removedField_isIgnoredWhenPresentInStoredPayload() {
            // given -- writer schema with extra value4 field (simulates event stored before value4 was removed)
            Schema writerSchema = ComplexObjectSchemas.compatibleSchemaWithAdditionalField;
            SchemaStore.Cache store = new SchemaStore.Cache();
            store.addSchema(ComplexObject.getClassSchema());
            store.addSchema(writerSchema);
            AvroConverter converter = new AvroConverter(store, c -> c);

            GenericData.Record storedRecord = new GenericData.Record(writerSchema);
            storedRecord.put("value1", "hello");
            storedRecord.put("value2", "world");
            storedRecord.put("value3", 10);
            storedRecord.put("value4", "to be ignored");
            byte[] storedBytes = TO_BYTES.convert(storedRecord);

            // when -- AF5 Avro converter reads the stored bytes; reader schema has no value4
            ComplexObject result = converter.convert(storedBytes, ComplexObject.class);

            // then -- known fields preserved; removed value4 silently dropped
            assertThat(result).isNotNull();
            assertThat(result.getValue1()).isEqualTo("hello");
            assertThat(result.getValue2()).isEqualTo("world");
            assertThat(result.getValue3()).isEqualTo(10);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 3 -- Renamed a field (via Avro alias)
    //
    // value1 was formerly named "oldValue1" in the writer schema.
    // Avro maps the old field name to the current field name via "aliases" declared
    // on the reader schema field. No change to stored bytes is required.
    //
    // The first test shows the intermediate GenericRecord step: ByteArrayToGenericRecordConverter
    // uses the writer schema as both writer and reader, so the field is still "oldValue1".
    // The second test shows the full resolution path: decoding into ComplexObjectWithRenamedValue1
    // (a SpecificRecordBase whose schema declares value1 with alias oldValue1) produces an object
    // where getValue1() returns the stored value -- alias resolved at the SpecificRecordBase level.
    // -------------------------------------------------------------------------

    @Nested
    class RenamedField {

        private final Schema writerSchema = new Schema.Parser().parse(
                "{" +
                "  \"type\": \"record\"," +
                "  \"name\": \"ComplexObject\"," +
                "  \"namespace\": \"org.axonframework.conversion.avro.test\"," +
                "  \"fields\": [" +
                "    {\"name\": \"oldValue1\", \"type\": \"string\"}," +
                "    {\"name\": \"value2\", \"type\": \"string\", \"default\": \"default value\"}," +
                "    {\"name\": \"value3\", \"type\": \"int\"}" +
                "  ]" +
                "}"
        );

        @Test
        void storedValue_isPreservedUnderWriterFieldName_whenReadAsGenericRecord() {
            // given -- writer schema uses old field name "oldValue1"; only writer schema added to store
            SchemaStore.Cache store = new SchemaStore.Cache();
            store.addSchema(writerSchema);
            AvroConverter converter = new AvroConverter(store, c -> c);

            GenericData.Record storedRecord = new GenericData.Record(writerSchema);
            storedRecord.put("oldValue1", "renamed-value");
            storedRecord.put("value2", "world");
            storedRecord.put("value3", 10);
            byte[] storedBytes = TO_BYTES.convert(storedRecord);

            // when -- decoding to GenericRecord; ByteArrayToGenericRecordConverter uses the writer schema
            GenericRecord result = converter.convert(storedBytes, GenericRecord.class);

            // then -- value accessible via the writer schema's field name (alias resolution requires a typed class)
            assertThat(result).isNotNull();
            assertThat(result.get("oldValue1")).hasToString("renamed-value");
        }

        @Test
        void renamedField_isMappedViaAlias_whenDecodingIntoSpecificRecord() {
            // given -- writer schema uses old field name "oldValue1"; reader class declares alias on value1
            SchemaStore.Cache store = new SchemaStore.Cache();
            store.addSchema(writerSchema);
            AvroConverter converter = new AvroConverter(store, c -> c);

            GenericData.Record storedRecord = new GenericData.Record(writerSchema);
            storedRecord.put("oldValue1", "renamed-value");
            storedRecord.put("value2", "world");
            storedRecord.put("value3", 10);
            byte[] storedBytes = TO_BYTES.convert(storedRecord);

            // when -- SpecificRecordBaseConverterStrategy resolves "oldValue1" to "value1" via the alias
            ComplexObjectWithRenamedValue1 result =
                    converter.convert(storedBytes, ComplexObjectWithRenamedValue1.class);

            // then -- value accessible via the new field name; alias resolved at SpecificRecordBase level
            assertThat(result).isNotNull();
            assertThat(result.getValue1()).isEqualTo("renamed-value");
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 4 -- Changed a field to a compatible type
    //
    // value3 was stored as int; Avro's schema resolution supports int-to-long
    // type promotion natively when the reader schema declares the field as long.
    //
    // The first test shows the intermediate GenericRecord step: ByteArrayToGenericRecordConverter
    // uses the writer schema as both writer and reader, so the stored int type is preserved.
    // The second test shows the full promotion path: decoding into ComplexObjectWithLongValue3
    // (a SpecificRecordBase whose schema declares value3 as long) widens the stored int to long
    // automatically -- the SpecificRecordBaseConverterStrategy handles that path via BinaryMessageDecoder.
    // -------------------------------------------------------------------------

    @Nested
    class CompatibleTypeChange {

        @Test
        void storedIntValue_isPreservedWhenReadAsGenericRecord() {
            // given -- writer schema with value3 as int
            Schema writerSchema = ComplexObjectSchemas.compatibleSchema;
            SchemaStore.Cache store = new SchemaStore.Cache();
            store.addSchema(writerSchema);
            AvroConverter converter = new AvroConverter(store, c -> c);

            GenericData.Record storedRecord = new GenericData.Record(writerSchema);
            storedRecord.put("value1", "hello");
            storedRecord.put("value2", "world");
            storedRecord.put("value3", 42);
            byte[] storedBytes = TO_BYTES.convert(storedRecord);

            // when -- decoding to GenericRecord preserves the writer schema's int type
            GenericRecord result = converter.convert(storedBytes, GenericRecord.class);

            // then -- int value preserved; widening to long applies when decoding into a typed class with long field
            assertThat(result).isNotNull();
            assertThat(result.get("value3")).isEqualTo(42);
        }

        @Test
        void intWidenedToLong_whenDecodingIntoSpecificRecord() {
            // given -- writer schema with value3 as int; reader class declares value3 as long
            Schema writerSchema = ComplexObjectSchemas.compatibleSchema;
            SchemaStore.Cache store = new SchemaStore.Cache();
            store.addSchema(writerSchema);
            AvroConverter converter = new AvroConverter(store, c -> c);

            GenericData.Record storedRecord = new GenericData.Record(writerSchema);
            storedRecord.put("value1", "hello");
            storedRecord.put("value2", "world");
            storedRecord.put("value3", 42);
            byte[] storedBytes = TO_BYTES.convert(storedRecord);

            // when -- SpecificRecordBaseConverterStrategy widens int to long via reader schema type promotion
            ComplexObjectWithLongValue3 result =
                    converter.convert(storedBytes, ComplexObjectWithLongValue3.class);

            // then -- int value widened to long; accessible as 42L
            assertThat(result).isNotNull();
            assertThat(result.getValue3()).isEqualTo(42L);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 6 -- Handler wants a different representation
    //
    // Two handlers read the same stored bytes: one requests the typed
    // ComplexObject class; the other requests a GenericRecord.
    // The AvroConverter converts to whichever type is requested.
    // -------------------------------------------------------------------------

    @Nested
    class HandlerReceivesDifferentRepresentation {

        @Test
        void differentHandlers_receiveTheSameStoredBytes_inDifferentRepresentations() {
            // given -- one stored event as Avro binary bytes
            SchemaStore.Cache store = new SchemaStore.Cache();
            store.addSchema(ComplexObject.getClassSchema());
            AvroConverter converter = new AvroConverter(store, c -> c);

            ComplexObject original = ComplexObject.newBuilder()
                                                  .setValue1("hello")
                                                  .setValue2("world")
                                                  .setValue3(42)
                                                  .build();
            byte[] storedBytes = TO_BYTES.convert(original);

            // when -- handler A declares the typed class; handler B declares GenericRecord
            ComplexObject typedResult = converter.convert(storedBytes, ComplexObject.class);
            GenericRecord genericResult = converter.convert(storedBytes, GenericRecord.class);

            // then -- handler A receives the typed object with correct field values
            assertThat(typedResult).isNotNull();
            assertThat(typedResult.getValue1()).isEqualTo("hello");
            assertThat(typedResult.getValue2()).isEqualTo("world");
            assertThat(typedResult.getValue3()).isEqualTo(42);

            // and -- handler B receives the same data as a GenericRecord; no generated class needed
            assertThat(genericResult).isNotNull();
            assertThat(genericResult.get("value1")).hasToString("hello");
            assertThat(genericResult.get("value2")).hasToString("world");
            assertThat(genericResult.get("value3")).isEqualTo(42);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 7 -- Format-agnosticism: event class unchanged when format is Avro
    //
    // The AvroConverter is a drop-in replacement for Jackson2Converter inside
    // DelegatingEventConverter. Switching the serialization format to Avro
    // requires only reconfiguring the EventConverter -- event classes are
    // unchanged.
    //
    // Demonstrated by reading Avro-stored bytes through the AvroConverter using
    // the same unchanged ComplexObject event class.
    // -------------------------------------------------------------------------

    @Nested
    class SwitchedSerializationFormat {

        @Test
        void eventClass_requiresNoChange_whenSerializationFormatIsAvro() {
            // given -- payload stored as Avro binary bytes (format switched from, e.g., JSON to Avro)
            SchemaStore.Cache store = new SchemaStore.Cache();
            store.addSchema(ComplexObject.getClassSchema());
            AvroConverter converter = new AvroConverter(store, c -> c);

            ComplexObject original = ComplexObject.newBuilder()
                                                  .setValue1("hello")
                                                  .setValue2("world")
                                                  .setValue3(42)
                                                  .build();
            byte[] storedBytes = TO_BYTES.convert(original);

            // when -- AvroConverter converts Avro bytes to the event class; only the converter config changed
            ComplexObject result = converter.convert(storedBytes, ComplexObject.class);

            // then -- handler receives correct data; event class unchanged
            assertThat(result).isNotNull();
            assertThat(result.getValue1()).isEqualTo("hello");
            assertThat(result.getValue2()).isEqualTo("world");
            assertThat(result.getValue3()).isEqualTo(42);
        }
    }
}
