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

package org.axonframework.examples.demo.coursecatalog.catalog.testutil;

import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Captured result of running a chain on a single input: either a list of output events
 * (success path) or a thrown exception. Shared by {@link TransformationTester} and
 * {@link ChainTester} so both expose the same {@code then()} assertions.
 */
final class TransformationOutcome {

    private final EventMessage input;
    private final List<EventMessage> outputs;
    private final @Nullable Throwable thrown;

    private TransformationOutcome(EventMessage input, List<EventMessage> outputs, @Nullable Throwable thrown) {
        this.input = input;
        this.outputs = outputs;
        this.thrown = thrown;
    }

    static TransformationOutcome run(
            EventTransformerChain chain,
            MessageType inputType,
            Object inputPayload,
            MessageConverter converter,
            MessageTypeResolver typeResolver
    ) {
        return run(chain, inputType, inputPayload, Metadata.emptyInstance(), converter, typeResolver);
    }

    static TransformationOutcome run(
            EventTransformerChain chain,
            MessageType inputType,
            Object inputPayload,
            Metadata inputMetadata,
            MessageConverter converter,
            MessageTypeResolver typeResolver
    ) {
        EventMessage input = new GenericEventMessage(inputType, inputPayload, inputMetadata);
        try {
            List<EventMessage> collected = collect(chain.transform(
                    MessageStream.fromIterable(List.of(input)),
                    null,
                    converter,
                    typeResolver
            ));
            return new TransformationOutcome(input, collected, null);
        } catch (RuntimeException e) {
            return new TransformationOutcome(input, List.of(), e);
        }
    }

    EventMessage input() {
        return input;
    }

    List<EventMessage> outputs() {
        requireSuccess();
        return List.copyOf(outputs);
    }

    void requireSuccess() {
        if (thrown != null) {
            throw new AssertionError("Expected success but transformer threw: " + thrown, thrown);
        }
    }

    Throwable requireException() {
        if (thrown == null) {
            throw new AssertionError("Expected an exception but transformer ran successfully");
        }
        return thrown;
    }

    private static List<EventMessage> collect(MessageStream<? extends EventMessage> stream) {
        List<EventMessage> collected = new ArrayList<>();
        stream.<Void>reduce(null, (acc, entry) -> {
            collected.add(entry.message());
            return null;
        }).join();
        return collected;
    }
}
