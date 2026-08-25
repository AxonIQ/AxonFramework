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

package org.axonframework.conversion.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import org.axonframework.messaging.core.Metadata;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * JsonDeserializer implementation for Jackson 2 that deserializes Metadata instances.
 *
 * @author Allard Buijze
 * @since 2.4.2
 * @deprecated in favor of the {@link JacksonConverter}.
 * TODO #3602 remove
 */
@Deprecated(forRemoval = true, since = "5.0.0")
public class MetadataDeserializer extends JsonDeserializer<Metadata> {

    @Override
    public Object deserializeWithType(JsonParser jsonParser, DeserializationContext ctxt, TypeDeserializer typeDeserializer) throws IOException {
        return typeDeserializer.deserializeTypedFromObject(jsonParser, ctxt);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Metadata deserialize(JsonParser jp, DeserializationContext ctxt)
            throws IOException {
        JsonDeserializer<Object> deserializer = ctxt.findRootValueDeserializer(
                ctxt.getTypeFactory().constructMapType(Map.class, String.class, Object.class));

        return Metadata.from((Map) deserializer.deserialize(jp, ctxt, new HashMap<>()));
    }
}
