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

package org.axonframework.conversion.avro.test;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecordBase;

/**
 * A minimal {@link SpecificRecordBase} for testing Avro alias-based field rename resolution.
 * <p>
 * The schema declares {@code value1} with alias {@code oldValue1}. When stored bytes were written using a writer schema
 * with field name {@code oldValue1}, Avro's writer/reader schema resolution maps the writer field to the reader field
 * via the alias -- no stored bytes need to change.
 * <p>
 * Used by {@link org.axonframework.conversion.avro.AvroPayloadEvolutionCapabilityTest}.
 */
public class ComplexObjectWithRenamedValue1 extends SpecificRecordBase {

    public static final Schema SCHEMA$ = new Schema.Parser().parse(
            "{\"type\":\"record\",\"name\":\"ComplexObjectWithRenamedValue1\","
                    + "\"namespace\":\"org.axonframework.conversion.avro.test\","
                    + "\"aliases\":[\"org.axonframework.conversion.avro.test.ComplexObject\"],"
                    + "\"fields\":["
                    + "{\"name\":\"value1\",\"type\":\"string\",\"aliases\":[\"oldValue1\"]},"
                    + "{\"name\":\"value2\",\"type\":\"string\",\"default\":\"default value\"},"
                    + "{\"name\":\"value3\",\"type\":\"int\"}"
                    + "]}");

    public static Schema getClassSchema() {
        return SCHEMA$;
    }

    private String value1;
    private String value2;
    private int value3;

    public ComplexObjectWithRenamedValue1() {
        // no-args constructor required by Avro
    }

    @Override
    public Schema getSchema() {
        return SCHEMA$;
    }

    @Override
    public Object get(int fieldIndex) {
        return switch (fieldIndex) {
            case 0 -> value1;
            case 1 -> value2;
            case 2 -> value3;
            default -> throw new IndexOutOfBoundsException("Invalid index: " + fieldIndex);
        };
    }

    @Override
    public void put(int fieldIndex, Object fieldValue) {
        switch (fieldIndex) {
            case 0 -> value1 = fieldValue != null ? fieldValue.toString() : null;
            case 1 -> value2 = fieldValue != null ? fieldValue.toString() : null;
            case 2 -> value3 = (Integer) fieldValue;
            default -> throw new IndexOutOfBoundsException("Invalid index: " + fieldIndex);
        }
    }

    public String getValue1() {
        return value1;
    }

    public String getValue2() {
        return value2;
    }

    public int getValue3() {
        return value3;
    }
}
