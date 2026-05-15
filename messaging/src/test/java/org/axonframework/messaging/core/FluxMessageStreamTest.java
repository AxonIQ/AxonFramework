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

package org.axonframework.messaging.core;

import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link FluxMessageStream} through the {@link MessageStreamTest} suite.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author John Hendrikx
 */
class FluxMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        return FluxUtils.asMessageStream(Flux.fromIterable(messages));
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        Assumptions.abort("FluxMessageStream doesn't support explicit single-value streams");
        return null;
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        Assumptions.abort("FluxMessageStream doesn't support explicitly empty streams");
        return null;
    }


    @Override
    protected MessageStream<Message> uncompletedTestSubject(List<Message> messages,
                                                            CompletableFuture<Void> completionMarker) {
        return FluxUtils.asMessageStream(Flux.fromIterable(messages).concatWith(
                Flux.create(emitter -> completionMarker.whenComplete(
                        (v, e) -> {
                            if (e != null) {
                                emitter.error(e);
                            } else {
                                emitter.complete();
                            }
                        })))
        );
    }

    @Override
    protected MessageStream<Message> failingTestSubject(List<Message> messages, RuntimeException failure) {
        return FluxUtils.asMessageStream(Flux.fromIterable(messages).concatWith(Mono.error(failure)));
    }

    @Override
    protected  Message createRandomMessage() {
        return new GenericMessage(new MessageType("message"),
                                    "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Test
    void testCallingCloseReleasesFluxSubscription() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        Flux<Message> flux;
        flux = Flux.fromIterable(List.of(createRandomMessage(), createRandomMessage(), createRandomMessage()))
                   .doOnCancel(() -> invoked.set(true));
        MessageStream<Message> testSubject = FluxUtils.asMessageStream(flux);

        assertTrue(testSubject.next().isPresent());
        assertFalse(invoked.get());
        testSubject.close();
        assertTrue(invoked.get());
    }

    @Test
    void whenFluxErrorsWhileConsumerIsAwaitingThenCallbackFires() throws InterruptedException {
        // A sink lets us emit errors on demand from the test thread
        Sinks.Many<Message> sink = Sinks.many().unicast().onBackpressureBuffer();
        MessageStream<Message> testSubject = FluxUtils.asMessageStream(sink.asFlux());

        // consumer calls next() on an empty flux -> NotReady -> awaitingData=true
        assertThat(testSubject.next()).isEmpty();
        assertThat(testSubject.isCompleted()).isFalse();

        CountDownLatch callbackFired = new CountDownLatch(1);

        testSubject.setCallback(callbackFired::countDown);

        // error arrives asynchronously from the producer side
        RuntimeException error = new RuntimeException("async flux error");

        sink.tryEmitError(error);

        // without signalProgress() in onError(), this times out
        assertThat(callbackFired.await(5, TimeUnit.SECONDS))
            .as("callback must fire when Flux errors while consumer is awaiting")
            .isTrue();

        // consuming the error completes the stream exceptionally
        assertThat(testSubject.next()).isEmpty();
        assertThat(testSubject.error()).contains(error);
    }
}