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
 * A minimal {@link SpecificRecordBase} for testing Avro int-to-long type promotion.
 * <p>
 * The schema declares {@code value3} as {@code long}. When stored bytes were written using a writer schema with
 * {@code value3} as {@code int}, Avro's writer/reader schema resolution widens the int to long automatically -- no
 * stored bytes need to change.
 * <p>
 * Used by {@link org.axonframework.conversion.avro.AvroPayloadEvolutionCapabilityTest}.
 */
public class ComplexObjectWithLongValue3 extends SpecificRecordBase {

    public static final Schema SCHEMA$ = new Schema.Parser().parse(
            "{\"type\":\"record\",\"name\":\"ComplexObjectWithLongValue3\","
                    + "\"namespace\":\"org.axonframework.conversion.avro.test\","
                    + "\"aliases\":[\"org.axonframework.conversion.avro.test.ComplexObject\"],"
                    + "\"fields\":["
                    + "{\"name\":\"value1\",\"type\":\"string\"},"
                    + "{\"name\":\"value2\",\"type\":\"string\",\"default\":\"default value\"},"
                    + "{\"name\":\"value3\",\"type\":\"long\"}"
                    + "]}");

    public static Schema getClassSchema() {
        return SCHEMA$;
    }

    private String value1;
    private String value2;
    private long value3;

    public ComplexObjectWithLongValue3() {
    }

    @Override
    public Schema getSchema() {
        return SCHEMA$;
    }

    @Override
    public Object get(int field$) {
        return switch (field$) {
            case 0 -> value1;
            case 1 -> value2;
            case 2 -> value3;
            default -> throw new IndexOutOfBoundsException("Invalid index: " + field$);
        };
    }

    @Override
    public void put(int field$, Object value$) {
        switch (field$) {
            case 0 -> value1 = value$ != null ? value$.toString() : null;
            case 1 -> value2 = value$ != null ? value$.toString() : null;
            case 2 -> value3 = (Long) value$;
            default -> throw new IndexOutOfBoundsException("Invalid index: " + field$);
        }
    }

    public String getValue1() {
        return value1;
    }

    public String getValue2() {
        return value2;
    }

    public long getValue3() {
        return value3;
    }
}
