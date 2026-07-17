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
package root.conversion.implementations.avro;

import org.apache.avro.message.SchemaStore;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.avro.AvroConverter;

class AvroConverterExample {

    // tag::build-avro-converter[]
    public Converter buildAvroConverter(SchemaStore schemaStore) {
        return new AvroConverter(
                schemaStore,
                config -> config  // Customize configuration here
        );
    }
    // end::build-avro-converter[]
}
