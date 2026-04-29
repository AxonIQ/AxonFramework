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

import org.axonframework.messaging.core.MessageStream.Entry;
import org.junit.Assume;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * Test suite for {@link MessageStream} implementations. To use, subclass
 * the suite and implement {@link #cases()} and/or {@link #errorCases()}.
 *
 * @author John Hendrikx
 */
@TestInstance(Lifecycle.PER_CLASS)  // so we can have instance methods that can supply parameters
public abstract class MessageStreamTestSuite {

    /**
     * Describes a successful {@link MessageStream} test case, specifying the stream under test
     * and its expected size.
     *
     * @param discriminator an optional label appended to the test name to distinguish cases with the same stream type;
     *                      may be {@code null}
     * @param expectedSize  the number of items the stream is expected to produce
     * @param stream        the stream under test
     */
    protected record Case(String discriminator, int expectedSize, MessageStream<? extends Message> stream) {

        /**
         * Creates a bounded test case without a discriminator label.
         *
         * @param expectedSize the number of items the stream is expected to produce
         * @param stream       the stream under test
         */
        public Case(int expectedSize, MessageStream<? extends Message> stream) {
            this(null, expectedSize, stream);
        }

        @Override
        public String toString() {
            return stream.getClass().getSimpleName() + (discriminator == null ? "" : "(" + discriminator + ")") + "[" + expectedSize + "]: " + stream;
        }
    }

    /**
     * Describes a {@link MessageStream} test case that ends with an error, specifying the stream
     * under test and the number of items expected before the error occurs.
     *
     * @param discriminator    an optional label appended to the test name to distinguish cases with the same stream
     *                         type; may be {@code null}
     * @param itemsBeforeError the number of items the stream is expected to produce before failing
     * @param stream           the stream under test
     */
    protected record ErrorCase(String discriminator, int itemsBeforeError, MessageStream<? extends Message> stream) {

        /**
         * Creates an error test case without a discriminator label.
         *
         * @param itemsBeforeError the number of items the stream is expected to produce before failing
         * @param stream           the stream under test
         */
        public ErrorCase(int itemsBeforeError, MessageStream<? extends Message> stream) {
            this(null, itemsBeforeError, stream);
        }

        @Override
        public String toString() {
            return stream.getClass().getSimpleName() + (discriminator == null ? "" : "(" + discriminator + ")") + "[" + itemsBeforeError + "+err]: " + stream;
        }
    }

    /**
     * Returns the list of successful stream test cases to run. Subclasses override this method
     * to supply the cases exercised by the inherited parameterized tests.
     *
     * @return list of {@link Case} instances; defaults to an empty list
     */
    protected List<Case> cases() {
        return List.of();
    }

    /**
     * Returns the list of error stream test cases to run. Subclasses override this method
     * to supply the cases exercised by the inherited parameterized tests.
     *
     * @return list of {@link ErrorCase} instances; defaults to an empty list
     */
    protected List<ErrorCase> errorCases() {
        return List.of();
    }

    private final Map<Integer, Message> cachedMessages = new HashMap<>();

    /**
     * Returns a {@link CompletableFuture} that completes with the cached {@link Entry} for the given
     * {@code index} after a 50 ms delay, simulating an asynchronously produced stream entry.
     *
     * @param index the message index identifying which entry to complete with
     * @return a future that delivers the entry after a short delay
     */
    protected final CompletableFuture<Entry<Message>> delayedEntry(int index) {
        CompletableFuture<Entry<Message>> cf = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> {
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());

            cf.complete(entry(index));
        });

        return cf;
    }

    /**
     * Returns a {@link CompletableFuture} that completes exceptionally after a 50 ms delay,
     * simulating a delayed error in an asynchronous stream.
     *
     * @return a future that fails with a {@link RuntimeException} after a short delay
     */
    protected final CompletableFuture<Entry<Message>> delayedFailedEntry() {
        CompletableFuture<Entry<Message>> cf = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> {
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());

            cf.completeExceptionally(new RuntimeException("delayed-failed-entry"));
        });

        return cf;
    }

    /**
     * Creates a new {@link Entry} wrapping the cached {@link Message} for the given {@code index}.
     *
     * @param index the message index
     * @return an entry containing the message at the given index
     */
    protected final Entry<Message> entry(int index) {
        return new SimpleEntry<>(msg(index));
    }

    /**
     * Returns the cached {@link Message} for the given {@code index}, creating it on first access.
     * The same instance is returned for equal indices, enabling identity-based assertions across tests.
     *
     * @param index the message index, used as the message payload
     * @return the cached message for the given index
     */
    protected final Message msg(int index) {
        return cachedMessages.computeIfAbsent(index, k -> new GenericMessage(new MessageType(String.class), index) {
            @Override
            public String toString() {
                return "Message[" + index + "]";
            }
        });
    }

    @ParameterizedTest(allowZeroInvocations = true, name = "{0}")
    @MethodSource("cases")
    void iterateTest(Case c) throws Exception {
        assertThat(iterate(c)).describedAs("iterate").isEqualTo(range(0, c.expectedSize));
    }

    @ParameterizedTest(allowZeroInvocations = true, name = "{0}")
    @MethodSource("cases")
    void reduceTest(Case c) throws Exception {
        assertThat(reduce(c)).describedAs("reduce").isEqualTo(range(0, c.expectedSize));
    }

    @ParameterizedTest(allowZeroInvocations = true, name = "{0}")
    @MethodSource("cases")
    void mapTest(Case c) throws Exception {
        assertThat(iterate(map(c))).describedAs("map").isEqualTo(range(100, c.expectedSize));
    }

    @ParameterizedTest(allowZeroInvocations = true, name = "{0}")
    @MethodSource("cases")
    void nextThenReduceTest(Case c) throws Exception {
        Assume.assumeTrue(c.expectedSize > 0);

        assertThat(next(c)).describedAs("next() = " + msg(0)).isEqualTo(msg(0));
        assertThat(reduce(c)).describedAs("reduce").isEqualTo(range(1, c.expectedSize - 1));
    }

    @ParameterizedTest(allowZeroInvocations = true, name = "{0}")
    @MethodSource("cases")
    void nextThenMapTest(Case c) throws Exception {
        Assume.assumeTrue(c.expectedSize > 0);

        assertThat(next(c)).describedAs("next() = " + msg(0)).isEqualTo(msg(0));
        assertThat(iterate(map(c))).describedAs("map").isEqualTo(range(101, c.expectedSize - 1));
    }

    @ParameterizedTest(allowZeroInvocations = true, name = "{0}")
    @MethodSource("cases")
    void peekThenMapTest(Case c) throws Exception {
        Assume.assumeTrue(c.expectedSize > 0);

        assertThat(peek(c)).describedAs("peek() = " + msg(0)).isEqualTo(msg(0));
        assertThat(iterate(map(c))).describedAs("map").isEqualTo(range(100, c.expectedSize));
    }

    @ParameterizedTest(allowZeroInvocations = true, name = "{0}")
    @MethodSource("cases")
    void peekThenReduceTest(Case c) throws Exception {
        Assume.assumeTrue(c.expectedSize > 0);

        assertThat(peek(c)).describedAs("peek() = " + msg(0)).isEqualTo(msg(0));
        assertThat(reduce(c)).describedAs("reduce").isEqualTo(range(0, c.expectedSize));
    }

    /**
     * Given an {@link ErrorCase} which produces a stream that produces an error
     * after {@link ErrorCase#itemsBeforeError} items, tests {@link MessageStream#onErrorContinue(java.util.function.Function)}
     * which recovers with two custom messages.
     *
     * @param c an {@link ErrorCase}, cannot be {@code null}
     * @throws Exception when a time-out occurs or another exception was thrown
     */
    @ParameterizedTest(allowZeroInvocations = true, name = "{0}")
    @MethodSource("errorCases")
    void onErrorContinueTest(ErrorCase c) throws Exception {
        List<Entry<Message>> recoveryEntries = List.of(entry(1000), entry(1001));
        MessageStream<Message> recovery = MessageStream.fromItems(
            recoveryEntries.stream().map(Entry::message).toArray(Message[]::new)
        );

        @SuppressWarnings("unchecked")
        MessageStream<Message> stream = (MessageStream<Message>) c.stream;
        MessageStream<Message> recovered = stream.onErrorContinue(e -> recovery);

        assertThat(reduce(recovered)).isEqualTo(Stream.concat(
            range(0, c.itemsBeforeError).stream(),
            recoveryEntries.stream().map(Entry::message)
        ).toList());
    }

    private Message next(Case c) throws Exception {
        await().alias("stream::hasNextAvailable").until(c.stream::hasNextAvailable);

        return c.stream.next().map(Entry::message).orElseThrow();
    }

    private Message peek(Case c) throws Exception {
        await().alias("stream::hasNextAvailable").until(c.stream::hasNextAvailable);

        return c.stream.peek().map(Entry::message).orElseThrow();
    }

    private List<? extends Message> iterate(Case c) throws Exception {
        return iterate(c.stream);
    }

    private List<? extends Message> iterate(MessageStream<?> stream) {
        List<Message> list = new ArrayList<>();
        long nanos = System.nanoTime();

        while (!stream.isCompleted()) {
            while (stream.hasNextAvailable()) {
                list.add(stream.next().map(Entry::message).orElseThrow());
            }

            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());  // make it a bit more CPU friendly

            if (System.nanoTime() - nanos > 10_000_000_000L) {
                fail("Iteration timed out for stream: " + stream);
            }
        }

        return list;
    }

    private List<? extends Message> reduce(Case c) throws Exception {
        return reduce(c.stream);
    }

    private List<? extends Message> reduce(MessageStream<?> stream) throws Exception {
        return stream.reduce(
            List.<Message>of(),
            (s, entry) -> Stream.concat(s.stream(), Stream.of(entry == null ? null : entry.message())).toList()
        ).get(10, TimeUnit.SECONDS);
    }

    private MessageStream<? extends Message> map(Case c) throws Exception {
        return c.stream.map(
            m -> new SimpleEntry<>(msg((int)m.message().payload() + 100))
        );
    }

    private List<? extends Message> range(int start, int total) {
        int end = start + total;

        return Stream.iterate(start, e -> e != end, e -> e + 1).map(this::msg).toList();
    }

    /*
     * Inlined tests for our standard streams:
     */

    static class EmptyTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new EmptyMessageStream<>())
            );
        }
    }

    static class SingleValueTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(1, new SingleValueMessageStream<>(entry(0))),
                new Case(1, new SingleValueMessageStream<>(delayedEntry(0)))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new SingleValueMessageStream<>(CompletableFuture.failedFuture(new RuntimeException("oops")))),
                new ErrorCase(0, new SingleValueMessageStream<>(delayedFailedEntry()))
            );
        }
    }

    static class FailedTest extends MessageStreamTestSuite {
        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new FailedMessageStream<>(new RuntimeException("oops")))
            );
        }
    }

    static class ConcatenatingTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new ConcatenatingMessageStream<>(MessageStream.empty(), MessageStream.empty())),
                new Case(3, new ConcatenatingMessageStream<>(MessageStream.empty(), MessageStream.fromItems(msg(0), msg(1), msg(2)))),
                new Case(3, new ConcatenatingMessageStream<>(MessageStream.just(msg(0)), MessageStream.fromItems(msg(1), msg(2)))),
                new Case(3, new ConcatenatingMessageStream<>(MessageStream.fromItems(msg(0), msg(1)), MessageStream.just(msg(2)))),
                new Case(3, new ConcatenatingMessageStream<>(MessageStream.fromItems(msg(0), msg(1), msg(2)), MessageStream.empty()))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new ConcatenatingMessageStream<>(MessageStream.empty(), MessageStream.failed(new RuntimeException("oops")))),
                new ErrorCase(0, new ConcatenatingMessageStream<>(MessageStream.failed(new RuntimeException("oops")), MessageStream.empty())),
                new ErrorCase(2, new ConcatenatingMessageStream<>(MessageStream.fromItems(msg(0), msg(1)), MessageStream.failed(new RuntimeException("oops")))),
                new ErrorCase(0, new ConcatenatingMessageStream<>(MessageStream.failed(new RuntimeException("oops")), MessageStream.fromItems(msg(0), msg(1))))
            );
        }
    }

    static class CloseCallbackTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new CloseCallbackMessageStream<>(MessageStream.empty(), () -> {})),
                new Case(1, new CloseCallbackMessageStream<>(MessageStream.just(msg(0)), () -> {})),
                new Case(2, new CloseCallbackMessageStream<>(MessageStream.fromItems(msg(0), msg(1)), () -> {}))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new CloseCallbackMessageStream<>(MessageStream.failed(new RuntimeException("oops")), () -> {}))
            );
        }
    }

    static class CompletionCallbackTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new CompletionCallbackMessageStream<>(MessageStream.empty(), () -> {})),
                new Case(1, new CompletionCallbackMessageStream<>(MessageStream.just(msg(0)), () -> {})),
                new Case(2, new CompletionCallbackMessageStream<>(MessageStream.fromItems(msg(0), msg(1)), () -> {}))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new CompletionCallbackMessageStream<>(MessageStream.failed(new RuntimeException("oops")), () -> {}))
            );
        }
    }

    static class FilteringTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new FilteringMessageStream<>(MessageStream.empty(), m -> false)),
                new Case(0, new FilteringMessageStream<>(MessageStream.empty(), m -> true)),
                new Case(0, new FilteringMessageStream<>(MessageStream.just(msg(0)), m -> false)),
                new Case(1, new FilteringMessageStream<>(MessageStream.just(msg(0)), m -> true)),
                new Case(0, new FilteringMessageStream<>(MessageStream.fromItems(msg(0), msg(1)), m -> false)),
                new Case(1, new FilteringMessageStream<>(MessageStream.fromItems(msg(0), msg(1)), m -> m.message().payload().toString().equals("0"))),
                new Case(1, new FilteringMessageStream<>(MessageStream.fromItems(msg(1), msg(0)), m -> m.message().payload().toString().equals("0"))),
                new Case(2, new FilteringMessageStream<>(MessageStream.fromItems(msg(0), msg(1)), m -> true))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new FilteringMessageStream<>(MessageStream.failed(new RuntimeException("oops")), m -> true))
            );
        }
    }

    static class FluxTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new FluxMessageStream<>(reactor.core.publisher.Flux.empty())),
                new Case(1, new FluxMessageStream<>(reactor.core.publisher.Flux.just(entry(0)))),
                new Case(3, new FluxMessageStream<>(reactor.core.publisher.Flux.fromIterable(List.of(entry(0), entry(1), entry(2))))),
                hotStream(0),
                hotStream(1),
                hotStream(3)
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new FluxMessageStream<>(reactor.core.publisher.Flux.error(new RuntimeException("cold-error")))),
                new ErrorCase(2, new FluxMessageStream<>(reactor.core.publisher.Flux.concat(
                    reactor.core.publisher.Flux.fromIterable(List.of(entry(0), entry(1))),
                    reactor.core.publisher.Flux.error(new RuntimeException("cold-error"))
                ))),
                hotErrorStream(0),
                hotErrorStream(2)
            );
        }

        private Case hotStream(int size) {
            Many<Entry<Message>> sink = Sinks.many().unicast().<Entry<Message>>onBackpressureBuffer();
            List<Entry<Message>> entries = new ArrayList<>();

            for (int i = 0; i < size; i++) {
                entries.add(entry(i));
            }

            Thread.ofVirtual().start(() -> {
                for (Entry<Message> e : entries) {
                    LockSupport.parkNanos(Duration.ofMillis(10).toNanos());

                    sink.tryEmitNext(e);
                }

                sink.tryEmitComplete();
            });

            return new Case("hot", size, new FluxMessageStream<>(sink.asFlux()));
        }

        private ErrorCase hotErrorStream(int itemsBeforeError) {
            Many<Entry<Message>> sink = Sinks.many().unicast().<Entry<Message>>onBackpressureBuffer();
            List<Entry<Message>> entries = new ArrayList<>();

            for (int i = 0; i < itemsBeforeError; i++) {
                entries.add(entry(i));
            }

            Thread.ofVirtual().start(() -> {
                for (Entry<Message> e : entries) {
                    LockSupport.parkNanos(Duration.ofMillis(10).toNanos());

                    sink.tryEmitNext(e);
                }

                sink.tryEmitError(new RuntimeException("hot-error"));
            });

            return new ErrorCase("hot", itemsBeforeError, new FluxMessageStream<>(sink.asFlux()));
        }
    }

    static class IgnoreEntriesTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new IgnoredEntriesMessageStream<>(MessageStream.empty())),
                new Case(0, new IgnoredEntriesMessageStream<>(MessageStream.just(msg(0)))),
                new Case(0, new IgnoredEntriesMessageStream<>(MessageStream.fromItems(msg(0), msg(1))))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new IgnoredEntriesMessageStream<>(MessageStream.failed(new RuntimeException("oops"))))
            );
        }
    }

    static class IteratorTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new IteratorMessageStream<>(List.<Entry<Message>>of().iterator())),
                new Case(1, new IteratorMessageStream<>(List.of(entry(0)).iterator())),
                new Case(3, new IteratorMessageStream<>(List.of(entry(0), entry(1), entry(2)).iterator()))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new IteratorMessageStream<>(new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        throw new RuntimeException("Bad iterator");
                    }

                    @Override
                    public Entry<Message> next() {
                        throw new RuntimeException("Bad iterator");
                    }
                }))
            );
        }
    }

    static class OnErrorContinueTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new OnErrorContinueMessageStream<>(MessageStream.empty(), e -> { throw new AssertionError(); })),
                new Case(0, new OnErrorContinueMessageStream<>(new SingleValueMessageStream<>(delayedFailedEntry()), e -> MessageStream.empty())),
                new Case(0, new OnErrorContinueMessageStream<>(MessageStream.failed(new RuntimeException("oops")), e -> MessageStream.empty())),
                new Case(2, new OnErrorContinueMessageStream<>(MessageStream.fromItems(msg(0), msg(1)), e -> { throw new AssertionError(); })),
                new Case(2, new OnErrorContinueMessageStream<>(new SingleValueMessageStream<>(delayedFailedEntry()), e -> MessageStream.fromItems(msg(0), msg(1)))),
                new Case(2, new OnErrorContinueMessageStream<>(MessageStream.failed(new RuntimeException("oops")), e -> MessageStream.fromItems(msg(0), msg(1))))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new OnErrorContinueMessageStream<>(MessageStream.failed(new RuntimeException("oops-1")), e -> MessageStream.failed(new RuntimeException("oops-2"))))
            );
        }
    }

    static class TruncateFirstTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new TruncateFirstMessageStream<>(MessageStream.empty())),
                new Case(1, new TruncateFirstMessageStream<>(MessageStream.just(msg(0)))),
                new Case(1, new TruncateFirstMessageStream<>(MessageStream.fromItems(msg(0), msg(1)))),
                new Case(1, new TruncateFirstMessageStream<>(new SingleValueMessageStream<>(delayedEntry(0))))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new TruncateFirstMessageStream<>(MessageStream.failed(new RuntimeException("oops")))),
                new ErrorCase(0, new TruncateFirstMessageStream<>(new SingleValueMessageStream<>(CompletableFuture.failedFuture(new RuntimeException("oops")))))
            );
        }
    }
}
