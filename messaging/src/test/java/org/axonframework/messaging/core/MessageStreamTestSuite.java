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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * Test suite for {@link MessageStream} implementations. To use, subclass
 * the suite and implement {@link #cases()} and/or {@link #errorCases()}.
 * <p>
 * Subclasses can also implement {@link #producedCases()}, with streams built on sources that a
 * {@link Producer} feeds from threads of its own. These cases check how a stream treats its
 * sources: that it does not read from them on the thread that signals it, that
 * {@link MessageStream#isCompleted()} does not read from them, and that signals arriving while
 * its consumer handles a callback are not lost.
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
    public record Case(String discriminator, int expectedSize, MessageStream<? extends Message> stream) {

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
    public record ErrorCase(String discriminator, int itemsBeforeError, MessageStream<? extends Message> stream) {

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

    /**
     * Describes a {@link MessageStream} test case built on sources produced by a {@link Producer}. The stream under
     * test is created when the test runs, by applying {@code streamFactory} to that test's producer, which delivers
     * the entries with indexes {@code 0} up to {@code expectedSize}, unless the stream under test drops some.
     *
     * @param discriminator an optional label appended to the test name to distinguish cases; may be {@code null}
     * @param expectedSize  the number of items the stream under test is expected to produce
     * @param streamFactory creates the stream under test from the sources of the given producer
     */
    public record ProducedCase(String discriminator, int expectedSize,
                               Function<MessageStreamTestSuite.Producer, MessageStream<? extends Message>> streamFactory) {

        /**
         * Creates a produced test case without a discriminator label.
         *
         * @param expectedSize  the number of items the stream under test is expected to produce
         * @param streamFactory creates the stream under test from the sources of the given producer
         */
        public ProducedCase(int expectedSize,
                            Function<MessageStreamTestSuite.Producer, MessageStream<? extends Message>> streamFactory) {
            this(null, expectedSize, streamFactory);
        }

        @Override
        public String toString() {
            return "produced" + (discriminator == null ? "" : "(" + discriminator + ")") + "[" + expectedSize + "]";
        }
    }

    /**
     * Returns the list of produced stream test cases to run. Subclasses override this method to supply the cases
     * exercised by the inherited produced tests.
     *
     * @return list of {@link ProducedCase} instances; defaults to an empty list
     */
    protected List<ProducedCase> producedCases() {
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
    void collectTest(Case c) throws Exception {
        assertThat(collect(c)).describedAs("collect").isEqualTo(range(0, c.expectedSize));
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

    /**
     * Consumes the stream on the test thread, with a callback that only records that it was called. Any read from
     * a produced source on a producer thread must then come from the stream under test reacting to a signal.
     */
    @ParameterizedTest(allowZeroInvocations = true, name = "{0}")
    @MethodSource("producedCases")
    void callbacksDoNotReadOnTheSignallingThread(ProducedCase c) throws Exception {
        Producer producer = new Producer();
        MessageStream<? extends Message> stream = c.streamFactory().apply(producer);

        List<Message> received = consumeOnTestThread(stream, producer, false);

        assertThat(received).describedAs("received").isEqualTo(range(0, c.expectedSize));
        assertThat(producer.readsOnProducerThreads()).describedAs("reads on producer threads").isEmpty();
    }

    /**
     * Consumes the stream like {@link #callbacksDoNotReadOnTheSignallingThread(ProducedCase)}, but before every read
     * asks {@link MessageStream#isCompleted()} a few times, which must not read from the produced sources.
     */
    @ParameterizedTest(allowZeroInvocations = true, name = "{0}")
    @MethodSource("producedCases")
    void isCompletedDoesNotRead(ProducedCase c) throws Exception {
        Producer producer = new Producer();
        MessageStream<? extends Message> stream = c.streamFactory().apply(producer);

        List<Message> received = consumeOnTestThread(stream, producer, true);

        assertThat(received).describedAs("received").isEqualTo(range(0, c.expectedSize));
    }

    /**
     * Holds back the consumer's first callback while all remaining entries arrive. The consumer drained the stream
     * before that, so the stream must call it back again for it to see them.
     */
    @ParameterizedTest(allowZeroInvocations = true, name = "{0}")
    @MethodSource("producedCases")
    void signalsArrivingDuringACallbackAreNotLost(ProducedCase c) throws Exception {
        Producer producer = new Producer();
        MessageStream<? extends Message> stream = c.streamFactory().apply(producer);
        List<Message> received = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch firstCallback = new CountDownLatch(1);
        AtomicBoolean holdFirstCallback = new AtomicBoolean(true);

        stream.setCallback(() -> {
            synchronized (received) {
                drain(stream, received);
            }

            if (stream.isCompleted()) {
                completed.countDown();
            }
            else if (holdFirstCallback.compareAndSet(true, false)) {
                firstCallback.countDown();
                producer.deliverRemainingAndWait();  // every remaining signal arrives while this callback runs
            }
        });

        producer.deliverNextAndWait();

        if (!firstCallback.await(2, TimeUnit.SECONDS)) {
            producer.deliverRemainingAndWait();  // the first entry caused no callback, so there was nothing to hold
        }

        assertThat(completed.await(10, TimeUnit.SECONDS)).describedAs("completed: " + stream).isTrue();

        synchronized (received) {
            assertThat(received).describedAs("received").isEqualTo(range(0, c.expectedSize));
        }
    }

    /**
     * Consumes the given stream on the test thread until it completes, reading only after its callback was called.
     * When {@code checkIsCompleted} is set, asks {@link MessageStream#isCompleted()} before every read and fails if
     * that read from the produced sources.
     */
    private List<Message> consumeOnTestThread(MessageStream<? extends Message> stream,
                                              Producer producer,
                                              boolean checkIsCompleted) throws InterruptedException {
        Semaphore signals = new Semaphore(0);
        List<Message> received = new ArrayList<>();

        stream.setCallback(signals::release);
        producer.deliverAll();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (true) {
            if (checkIsCompleted) {
                int readsBefore = producer.reads();

                stream.isCompleted();
                stream.isCompleted();

                assertThat(producer.reads()).describedAs("reads by isCompleted()").isEqualTo(readsBefore);
            }

            drain(stream, received);

            if (stream.isCompleted()) {
                return received;
            }

            long remaining = deadline - System.nanoTime();

            if (!signals.tryAcquire(Math.max(0, remaining), TimeUnit.NANOSECONDS)) {
                fail("Stream did not complete, received " + received + ": " + stream);
            }
        }
    }

    private static void drain(MessageStream<? extends Message> stream, List<Message> received) {
        while (stream.hasNextAvailable()) {
            stream.next().ifPresent(e -> received.add(e.message()));
        }
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

    private List<? extends Message> collect(Case c) throws Exception {
        return collect(c.stream);
    }

    private List<? extends Message> collect(MessageStream<?> stream) throws Exception {
        return stream.collect(ArrayList<Message>::new, List::add).get(10, TimeUnit.SECONDS);
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

    /**
     * Creates sources for a {@link ProducedCase} and feeds them from producer threads of its own, the way a storage
     * engine or a remote connection feeds a stream. Entries are delivered in index order across all sources it created,
     * and each source is sealed right after its last entry, so the order in which entries arrive is the same on every
     * run. The producer records every read from its sources, and which of those happened on a producer thread.
     */
    public final class Producer {

        private final List<ProducedStream> streams = new ArrayList<>();
        private final NavigableMap<Integer, ProducedStream> deliveries = new TreeMap<>();
        private final Set<Thread> producerThreads = ConcurrentHashMap.newKeySet();
        private final List<String> readsOnProducerThreads = new CopyOnWriteArrayList<>();
        private final AtomicInteger reads = new AtomicInteger();

        /**
         * Creates a source that will produce the entries with the given indexes, in the given order.
         *
         * @param indexes the indexes of the entries this source produces, each used by one source only
         * @return a source fed by this producer
         */
        public MessageStream<Message> stream(int... indexes) {
            ProducedStream stream = new ProducedStream(streams.size());

            for (int index : indexes) {
                if (deliveries.put(index, stream) != null) {
                    throw new IllegalArgumentException("Index " + index + " is produced by more than one source");
                }
            }

            streams.add(stream);

            return stream;
        }

        int reads() {
            return reads.get();
        }

        List<String> readsOnProducerThreads() {
            return readsOnProducerThreads;
        }

        /**
         * Delivers all entries from a producer thread, a few milliseconds apart, without waiting for that.
         */
        void deliverAll() {
            startProducer(() -> {
                while (deliverNext()) {
                    LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
                }
            });
        }

        /**
         * Delivers the next entry from a producer thread, and waits until that was done.
         */
        void deliverNextAndWait() throws InterruptedException {
            join(startProducer(this::deliverNext));
        }

        /**
         * Delivers all remaining entries from a producer thread, and waits until that was done.
         */
        void deliverRemainingAndWait() {
            try {
                join(startProducer(() -> {
                    while (deliverNext()) {
                        // deliver everything
                    }
                }));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                throw new IllegalStateException(e);
            }
        }

        /**
         * Delivers the next entry, then seals every source that has nothing left to deliver.
         *
         * @return {@code true} if more entries remain to be delivered
         */
        private boolean deliverNext() {
            Map.Entry<Integer, ProducedStream> next;

            synchronized (this) {
                next = deliveries.pollFirstEntry();
            }

            if (next != null) {
                next.getValue().deliver(entry(next.getKey()));
            }

            for (ProducedStream stream : streams) {
                boolean finished;

                synchronized (this) {
                    finished = !deliveries.containsValue(stream);
                }

                if (finished) {
                    stream.seal();
                }
            }

            synchronized (this) {
                return !deliveries.isEmpty();
            }
        }

        private Thread startProducer(Runnable action) {
            Thread thread = Thread.ofPlatform().name("producer").unstarted(action);

            producerThreads.add(thread);
            thread.start();

            return thread;
        }

        private void join(Thread thread) throws InterruptedException {
            if (!thread.join(Duration.ofSeconds(10))) {
                throw new IllegalStateException("Producer did not finish delivering");
            }
        }

        /**
         * Source fed by its {@link Producer}. Reports completion from what it holds, without reading.
         */
        private final class ProducedStream extends AbstractMessageStream<Message> {

            private final Queue<Entry<Message>> buffer = new ConcurrentLinkedQueue<>();
            private final int number;
            private volatile boolean sealed;

            ProducedStream(int number) {
                this.number = number;
            }

            void deliver(Entry<Message> entry) {
                buffer.add(entry);
                signalProgress();
            }

            void seal() {
                if (!sealed) {
                    sealed = true;
                    signalProgress();
                }
            }

            @Override
            protected FetchResult<Entry<Message>> fetchNext() {
                reads.incrementAndGet();

                if (producerThreads.contains(Thread.currentThread())) {
                    readsOnProducerThreads.add("source " + number + " was read on a producer thread");
                }

                boolean sealedNow = sealed;  // read before the buffer, as entries are added until it is sealed
                Entry<Message> entry = buffer.poll();

                if (entry != null) {
                    return FetchResult.of(entry);
                }

                return sealedNow ? FetchResult.completed() : FetchResult.notReady();
            }

            @Override
            protected FetchResult.NoValue<Entry<Message>> terminalState() {
                boolean sealedNow = sealed;

                return sealedNow && buffer.isEmpty() ? FetchResult.completed() : FetchResult.notReady();
            }

            @Override
            protected String describeFlags() {
                return "source-" + number + (sealed ? "|SEALED" : "");
            }
        }
    }
}
