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

package org.axonframework.deadline.dbscheduler;

import com.github.kagkarlsson.scheduler.serializer.GsonSerializer;
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer;
import com.github.kagkarlsson.scheduler.serializer.JavaSerializer;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.deadline.GenericDeadlineMessage;
import org.axonframework.deadline.TestScopeDescriptor;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DbSchedulerBinaryDeadlineDetailsTest {

    private static final String TEST_DEADLINE_NAME = "deadline-name";
    private static final String TEST_DEADLINE_PAYLOAD = "deadline-payload";
    private static final Metadata METADATA = getMetadata();
    private static final DeadlineMessage MESSAGE = getMessage();

    @MethodSource("dbSchedulerSerializers")
    @ParameterizedTest
    void shouldBeSerializable(Serializer serializer) {
        DbSchedulerBinaryDeadlineDetails expected = new DbSchedulerBinaryDeadlineDetails(
                "deadlineName",
                "deadlineName",
                "someScope".getBytes(),
                "org.axonframework.modelling.command.AggregateScopeDescriptor",
                "{\"foo\":\"bar\"}".getBytes(),
                "com.someCompany.api.ImportantEvent",
                "1",
                "{\"traceId\":\"1acc25e2-58a1-4dec-8b43-55388188500a\"}".getBytes()
        );
        byte[] serialized = serializer.serialize(expected);
        DbSchedulerBinaryDeadlineDetails result = serializer.deserialize(DbSchedulerBinaryDeadlineDetails.class,
                serialized);
        assertEquals(expected, result);
    }

    @MethodSource("axonSerializers")
    @ParameterizedTest
    void whenDataInPojoIsSerializedAndDeserializedItShouldBeTheSame(org.axonframework.conversion.Serializer serializer) {
        String expectedType = "aggregateType";
        String expectedIdentifier = "identifier";
        ScopeDescriptor descriptor = new TestScopeDescriptor(expectedType, expectedIdentifier);
        DbSchedulerBinaryDeadlineDetails result = DbSchedulerBinaryDeadlineDetails.serialized(
                TEST_DEADLINE_NAME, descriptor, MESSAGE, serializer);

        assertEquals(TEST_DEADLINE_NAME, result.getD());
        assertEquals(descriptor, result.getDeserializedScopeDescriptor(serializer));
        DeadlineMessage resultMessage = result.asDeadLineMessage(serializer);
        assertNotNull(resultMessage);
        assertEquals(TEST_DEADLINE_PAYLOAD, resultMessage.payload());
        assertEquals(METADATA, resultMessage.metadata());
    }

    public static Collection<Serializer> dbSchedulerSerializers() {
        List<Serializer> serializers = new ArrayList<>();
        serializers.add(new JavaSerializer());
        serializers.add(new JacksonSerializer());
        serializers.add(new GsonSerializer());
        return serializers;
    }

    public static Collection<org.axonframework.conversion.Serializer> axonSerializers() {
        List<org.axonframework.conversion.Serializer> testConverterList = new ArrayList<>();
        testConverterList.add(org.axonframework.conversion.json.JacksonSerializer.defaultSerializer());
        return testConverterList;
    }

    private static Metadata getMetadata() {
        Map<String, String> map = new HashMap<>();
        map.put("someStringValue", "foo");
        map.put("someIntValue", "2");
        return new Metadata(map);
    }

    private static DeadlineMessage getMessage() {
        return new GenericDeadlineMessage(
                TEST_DEADLINE_NAME,
                new GenericMessage(new MessageType(TEST_DEADLINE_PAYLOAD.getClass()), TEST_DEADLINE_PAYLOAD),
                Instant::now
        ).withMetadata(METADATA);
    }
}
