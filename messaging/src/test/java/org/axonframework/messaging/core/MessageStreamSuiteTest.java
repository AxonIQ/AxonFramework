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
import org.junit.jupiter.api.Nested;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Runs the {@link MessageStreamTestSuite} against the {@link MessageStream} implementations of this package, with one
 * {@link Nested nested} class per implementation.
 *
 * @author John Hendrikx
 */
class MessageStreamSuiteTest {

    @Nested
    class EmptyTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new EmptyMessageStream<>())
            );
        }
    }

    @Nested
    class SingleValueTest extends MessageStreamTestSuite {
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

    @Nested
    class FailedTest extends MessageStreamTestSuite {
        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new FailedMessageStream<>(new RuntimeException("oops")))
            );
        }
    }

    @Nested
    class ConcatenatingTest extends MessageStreamTestSuite {
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

    @Nested
    class CloseCallbackTest extends MessageStreamTestSuite {
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

    @Nested
    class CompletionCallbackTest extends MessageStreamTestSuite {
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

    @Nested
    class FilteringTest extends MessageStreamTestSuite {
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

    @Nested
    class FluxTest extends MessageStreamTestSuite {
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

    @Nested
    class IgnoreEntriesTest extends MessageStreamTestSuite {
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

    @Nested
    class IteratorTest extends MessageStreamTestSuite {
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

    @Nested
    class OnErrorContinueTest extends MessageStreamTestSuite {
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

    @Nested
    class TruncateFirstTest extends MessageStreamTestSuite {
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

    @Nested
    class FlatMappedTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new FlatMappedMessageStream<>(MessageStream.empty(), e -> MessageStream.just(e.message()))),
                new Case(1, new FlatMappedMessageStream<>(MessageStream.just(msg(0)), e -> MessageStream.just(e.message()))),
                new Case(3, new FlatMappedMessageStream<>(MessageStream.fromItems(msg(0), msg(1), msg(2)), e -> MessageStream.just(e.message()))),
                new Case("filtered-all", 0, new FlatMappedMessageStream<>(MessageStream.fromItems(msg(0), msg(1)), e -> MessageStream.empty())),
                new Case("1-to-3", 3, new FlatMappedMessageStream<>(MessageStream.just(msg(0)), e -> MessageStream.fromItems(msg(0), msg(1), msg(2)))),
                new Case("async-inner", 1, new FlatMappedMessageStream<>(MessageStream.just(msg(0)), e -> new SingleValueMessageStream<>(delayedEntry(0))))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new FlatMappedMessageStream<>(MessageStream.failed(new RuntimeException("oops")), e -> MessageStream.just(e.message()))),
                new ErrorCase(2, new FlatMappedMessageStream<>(MessageStream.fromItems(msg(0), msg(1)).concatWith(MessageStream.failed(new RuntimeException("oops"))), e -> MessageStream.just(e.message()))),
                new ErrorCase("inner-error", 0, new FlatMappedMessageStream<>(MessageStream.just(msg(0)), e -> MessageStream.failed(new RuntimeException("inner-oops"))))
            );
        }
    }

    @Nested
    class MapMultiTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new MapMultiMessageStream<>(MessageStream.empty(), (e, c) -> c.accept(e))),
                new Case(1, new MapMultiMessageStream<>(MessageStream.just(msg(0)), (e, c) -> c.accept(e))),
                new Case(3, new MapMultiMessageStream<>(MessageStream.fromItems(msg(0), msg(1), msg(2)), (e, c) -> c.accept(e))),
                new Case("filtered-all", 0, new MapMultiMessageStream<>(MessageStream.fromItems(msg(0), msg(1)), (e, c) -> {})),
                new Case("async", 1, new MapMultiMessageStream<>(new SingleValueMessageStream<>(delayedEntry(0)), (e, c) -> c.accept(e)))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new MapMultiMessageStream<>(MessageStream.failed(new RuntimeException("oops")), (e, c) -> c.accept(e))),
                new ErrorCase(2, new MapMultiMessageStream<>(MessageStream.fromItems(msg(0), msg(1)).concatWith(MessageStream.failed(new RuntimeException("oops"))), (e, c) -> c.accept(e)))
            );
        }
    }

    @Nested
    class MergedTest extends MessageStreamTestSuite {
        private static final Comparator<Entry<Message>> BY_PAYLOAD = Comparator.comparingInt(e -> (int) e.message().payload());

        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new MergedMessageStream<>(BY_PAYLOAD, MessageStream.empty(), MessageStream.empty())),
                new Case(3, new MergedMessageStream<>(BY_PAYLOAD, MessageStream.empty(), MessageStream.fromItems(msg(0), msg(1), msg(2)))),
                new Case(3, new MergedMessageStream<>(BY_PAYLOAD, MessageStream.fromItems(msg(0), msg(1), msg(2)), MessageStream.empty())),
                new Case("interleaved", 3, new MergedMessageStream<>(BY_PAYLOAD, MessageStream.fromItems(msg(0), msg(2)), MessageStream.just(msg(1)))),
                new Case("nested", 4, new MergedMessageStream<>(BY_PAYLOAD, new MergedMessageStream<>(BY_PAYLOAD, MessageStream.fromItems(msg(0), msg(3)), MessageStream.just(msg(2))), MessageStream.just(msg(1)))),
                new Case("async-first", 1, new MergedMessageStream<>(BY_PAYLOAD, new SingleValueMessageStream<>(delayedEntry(0)), MessageStream.empty())),
                new Case("async-second", 1, new MergedMessageStream<>(BY_PAYLOAD, MessageStream.empty(), new SingleValueMessageStream<>(delayedEntry(0)))),
                new Case("async-both", 2, new MergedMessageStream<>(BY_PAYLOAD, new SingleValueMessageStream<>(delayedEntry(0)), new SingleValueMessageStream<>(entryAfter(1, Duration.ofMillis(250)))))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new MergedMessageStream<>(BY_PAYLOAD, MessageStream.failed(new RuntimeException("oops")), MessageStream.empty())),
                new ErrorCase(0, new MergedMessageStream<>(BY_PAYLOAD, MessageStream.empty(), MessageStream.failed(new RuntimeException("oops")))),
                new ErrorCase(2, new MergedMessageStream<>(BY_PAYLOAD, MessageStream.fromItems(msg(0), msg(1)).concatWith(MessageStream.failed(new RuntimeException("oops"))), MessageStream.empty())),
                new ErrorCase("async", 0, new MergedMessageStream<>(BY_PAYLOAD, new SingleValueMessageStream<>(delayedFailedEntry()), MessageStream.empty()))
            );
        }

        /**
         * Returns a future that completes with the entry for the given {@code index} after the given {@code delay},
         * from another thread, so that two merged sources produce in a known order.
         */
        private CompletableFuture<Entry<Message>> entryAfter(int index, Duration delay) {
            CompletableFuture<Entry<Message>> cf = new CompletableFuture<>();

            Thread.ofVirtual().start(() -> {
                LockSupport.parkNanos(delay.toNanos());

                cf.complete(entry(index));
            });

            return cf;
        }
    }

    @Nested
    class DelayedTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case("completed", 2, DelayedMessageStream.create(CompletableFuture.completedFuture(MessageStream.fromItems(msg(0), msg(1))))),
                new Case("async", 0, DelayedMessageStream.create(later(MessageStream::<Message>empty))),
                new Case("async", 1, DelayedMessageStream.create(later(() -> MessageStream.just(msg(0))))),
                new Case("async", 3, DelayedMessageStream.create(later(() -> MessageStream.fromItems(msg(0), msg(1), msg(2))))),
                new Case("async-inner", 1, DelayedMessageStream.create(later(() -> new SingleValueMessageStream<>(delayedEntry(0)))))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase("completed", 0, DelayedMessageStream.<Message>create(CompletableFuture.failedFuture(new RuntimeException("oops")))),
                new ErrorCase("async", 0, DelayedMessageStream.create(later(() -> { throw new RuntimeException("oops"); }))),
                new ErrorCase("async", 2, DelayedMessageStream.create(later(() -> MessageStream.fromItems(msg(0), msg(1)).concatWith(MessageStream.failed(new RuntimeException("oops"))))))
            );
        }

        /**
         * Returns a future that completes with the stream from the given {@code supplier} after a short delay, from
         * another thread, or completes exceptionally if the supplier throws.
         */
        private CompletableFuture<MessageStream<Message>> later(Supplier<MessageStream<Message>> supplier) {
            CompletableFuture<MessageStream<Message>> cf = new CompletableFuture<>();

            Thread.ofVirtual().start(() -> {
                LockSupport.parkNanos(Duration.ofMillis(50).toNanos());

                try {
                    cf.complete(supplier.get());
                }
                catch (RuntimeException e) {
                    cf.completeExceptionally(e);
                }
            });

            return cf;
        }
    }

    @Nested
    class LazyTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new LazyMessageStream<Message>(MessageStream::empty)),
                new Case(1, new LazyMessageStream<Message>(() -> MessageStream.just(msg(0)))),
                new Case(3, new LazyMessageStream<Message>(() -> MessageStream.fromItems(msg(0), msg(1), msg(2)))),
                new Case("async", 1, new LazyMessageStream<Message>(() -> new SingleValueMessageStream<>(delayedEntry(0))))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new LazyMessageStream<Message>(() -> MessageStream.failed(new RuntimeException("oops")))),
                new ErrorCase(2, new LazyMessageStream<Message>(() -> MessageStream.fromItems(msg(0), msg(1)).concatWith(MessageStream.failed(new RuntimeException("oops"))))),
                new ErrorCase("async", 0, new LazyMessageStream<Message>(() -> new SingleValueMessageStream<>(delayedFailedEntry())))
            );
        }
    }

    @Nested
    class MappedTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new MappedMessageStream<Message, Message>(MessageStream.empty(), e -> e)),
                new Case(1, new MappedMessageStream<Message, Message>(MessageStream.just(msg(0)), e -> e)),
                new Case(3, new MappedMessageStream<Message, Message>(MessageStream.fromItems(msg(0), msg(1), msg(2)), e -> e)),
                new Case("renumbered", 2, new MappedMessageStream<Message, Message>(MessageStream.fromItems(msg(1), msg(2)), e -> entry((int) e.message().payload() - 1))),
                new Case("async", 1, new MappedMessageStream<Message, Message>(new SingleValueMessageStream<>(delayedEntry(0)), e -> e))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new MappedMessageStream<Message, Message>(MessageStream.failed(new RuntimeException("oops")), e -> e)),
                new ErrorCase(2, new MappedMessageStream<Message, Message>(MessageStream.fromItems(msg(0), msg(1)).concatWith(MessageStream.failed(new RuntimeException("oops"))), e -> e)),
                new ErrorCase("async", 0, new MappedMessageStream<Message, Message>(new SingleValueMessageStream<>(delayedFailedEntry()), e -> e))
            );
        }
    }

    @Nested
    class OnNextTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, new OnNextMessageStream<>(MessageStream.empty(), e -> {})),
                new Case(1, new OnNextMessageStream<>(MessageStream.just(msg(0)), e -> {})),
                new Case(3, new OnNextMessageStream<>(MessageStream.fromItems(msg(0), msg(1), msg(2)), e -> {})),
                new Case("async", 1, new OnNextMessageStream<>(new SingleValueMessageStream<>(delayedEntry(0)), e -> {}))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, new OnNextMessageStream<>(MessageStream.failed(new RuntimeException("oops")), e -> {})),
                new ErrorCase(2, new OnNextMessageStream<>(MessageStream.fromItems(msg(0), msg(1)).concatWith(MessageStream.failed(new RuntimeException("oops"))), e -> {})),
                new ErrorCase("async", 0, new OnNextMessageStream<>(new SingleValueMessageStream<>(delayedFailedEntry()), e -> {}))
            );
        }
    }

    @Nested
    class QueueTest extends MessageStreamTestSuite {
        @Override
        protected List<Case> cases() {
            return List.of(
                new Case(0, filledQueue(0, null)),
                new Case(1, filledQueue(1, null)),
                new Case(3, filledQueue(3, null)),
                new Case("async", 0, producedQueue(0, null)),
                new Case("async", 3, producedQueue(3, null))
            );
        }

        @Override
        protected List<ErrorCase> errorCases() {
            return List.of(
                new ErrorCase(0, filledQueue(0, new RuntimeException("oops"))),
                new ErrorCase(2, filledQueue(2, new RuntimeException("oops"))),
                new ErrorCase("async", 0, producedQueue(0, new RuntimeException("oops"))),
                new ErrorCase("async", 2, producedQueue(2, new RuntimeException("oops")))
            );
        }

        /**
         * Returns a queue holding the given number of messages, sealed normally, or exceptionally with the given
         * {@code failure} if it is not {@code null}.
         */
        private QueueMessageStream<Message> filledQueue(int size, RuntimeException failure) {
            QueueMessageStream<Message> queue = new QueueMessageStream<>();

            for (int i = 0; i < size; i++) {
                queue.offer(msg(i), Context.empty());
            }

            seal(queue, failure);

            return queue;
        }

        /**
         * Returns a queue that another thread fills with the given number of messages, one at a time, and then seals
         * normally, or exceptionally with the given {@code failure} if it is not {@code null}.
         */
        private QueueMessageStream<Message> producedQueue(int size, RuntimeException failure) {
            QueueMessageStream<Message> queue = new QueueMessageStream<>();

            Thread.ofVirtual().start(() -> {
                for (int i = 0; i < size; i++) {
                    LockSupport.parkNanos(Duration.ofMillis(10).toNanos());

                    queue.offer(msg(i), Context.empty());
                }

                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());

                seal(queue, failure);
            });

            return queue;
        }

        private static void seal(QueueMessageStream<Message> queue, RuntimeException failure) {
            if (failure == null) {
                queue.seal();
            }
            else {
                queue.sealExceptionally(failure);
            }
        }
    }
}
