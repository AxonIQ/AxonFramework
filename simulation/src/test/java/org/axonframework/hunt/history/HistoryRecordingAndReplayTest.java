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

package org.axonframework.hunt.history;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that a recorded history survives the round trip to disk and back, and that reading it back neither loses
 * an operation nor silently resolves one whose outcome is unknown.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class HistoryRecordingAndReplayTest {

    private static final HistoryHeader HEADER =
            HistoryHeader.of("round-trip", 4242L, "in-memory", "compressed", Map.of("writers", "2"));

    @TempDir
    Path directory;

    private Path historyFile() {
        return directory.resolve("history.jsonl");
    }

    @Nested
    class RoundTrip {

        @Test
        void headerSurvivesTheRoundTripSoTheRunCanBeReproducedFromItsOwnHistory() {
            // given
            Path file = historyFile();

            // when
            try (HistoryRecorder recorder = HistoryRecorder.writingTo(file, HEADER)) {
                recorder.forProcess("p0", "node-a").info(HistoryOps.SCAN, null, Map.of("eventIds", List.of("e-1")));
            }

            // then
            HistoryHeader read = HistoryView.read(file).header();
            assertThat(read).isEqualTo(HEADER);
            assertThat(read.reproduceCommand()).contains("-Dhunt.seed=4242", "-Dhunt.scenario=round-trip");
        }

        @Test
        void invocationAndCompletionAreSeparateRecordsPairedByCorrelationId() {
            // given
            Path file = historyFile();

            // when
            try (HistoryRecorder recorder = HistoryRecorder.writingTo(file, HEADER)) {
                HistoryRecorder.ProcessRecorder writer = recorder.forProcess("p0", "node-a");
                writer.invoke(HistoryOps.APPEND, "acct:a1", Map.of("marker", 3L)).ok(Map.of("marker", 4L));
            }

            // then
            HistoryView view = HistoryView.read(file);
            assertThat(view.records()).hasSize(2);
            assertThat(view.records()).extracting(HistoryRecord::type)
                                      .containsExactly(RecordType.INVOKE, RecordType.OK);
            assertThat(view.operations()).singleElement()
                                         .satisfies(operation -> {
                                             assertThat(operation.outcome()).isEqualTo(Outcome.OK);
                                             assertThat(operation.invocation().longValue("marker", -1)).isEqualTo(3L);
                                             assertThat(operation.completion()).isNotNull();
                                         });
        }

        @Test
        void allFieldsOfARecordSurviveSerialization() {
            // given
            Path file = historyFile();

            // when
            try (HistoryRecorder recorder = HistoryRecorder.writingTo(file, HEADER)) {
                recorder.faultEpoch("partition-1");
                recorder.forProcess("p7", "node-b")
                        .invoke(HistoryOps.CLAIM, "segment-3", Map.of("nodeId", "n1"))
                        .fail("UnableToClaimTokenException", Map.of("owner", "n2"));
            }

            // then
            List<HistoryRecord> records = HistoryView.read(file).records();
            HistoryRecord invocation = records.getFirst();
            HistoryRecord completion = records.getLast();
            assertThat(invocation.process()).isEqualTo("p7");
            assertThat(invocation.node()).isEqualTo("node-b");
            assertThat(invocation.op()).isEqualTo(HistoryOps.CLAIM);
            assertThat(invocation.key()).isEqualTo("segment-3");
            assertThat(invocation.faultEpoch()).isEqualTo("partition-1");
            assertThat(invocation.stringValue("nodeId")).isEqualTo("n1");
            assertThat(invocation.wallTs()).isPositive();
            assertThat(completion.error()).isEqualTo("UnableToClaimTokenException");
            assertThat(completion.id()).isEqualTo(invocation.id());
        }

        @Test
        void recordsAreNumberedFromOneCounterEvenWhenManyThreadsRecordConcurrently() throws Exception {
            // given
            Path file = historyFile();
            int threads = 8;
            int perThread = 50;
            CountDownLatch start = new CountDownLatch(1);

            // when
            try (HistoryRecorder recorder = HistoryRecorder.writingTo(file, HEADER);
                 ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                List<java.util.concurrent.Future<?>> submitted = new ArrayList<>();
                for (int thread = 0; thread < threads; thread++) {
                    HistoryRecorder.ProcessRecorder writer = recorder.forProcess("p" + thread, null);
                    submitted.add(pool.submit(() -> {
                        start.await();
                        for (int op = 0; op < perThread; op++) {
                            writer.invoke(HistoryOps.APPEND, null, Map.of("n", op)).ok(Map.of());
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (java.util.concurrent.Future<?> future : submitted) {
                    future.get(30, TimeUnit.SECONDS);
                }
            }

            // then
            List<HistoryRecord> records = HistoryView.read(file).records();
            assertThat(records).hasSize(threads * perThread * 2);
            assertThat(records).extracting(HistoryRecord::idx)
                               .containsExactlyElementsOf(java.util.stream.LongStream.range(0, records.size())
                                                                                     .boxed()
                                                                                     .toList());
        }
    }

    @Nested
    class OpenHistoryDiscipline {

        @Test
        void anOperationThatNeverCompletedIsKeptAndReportedAsUnknown() {
            // given a run that ends while an append is still in flight
            Path file = historyFile();

            // when
            try (HistoryRecorder recorder = HistoryRecorder.writingTo(file, HEADER)) {
                HistoryRecorder.ProcessRecorder writer = recorder.forProcess("p0", null);
                writer.invoke(HistoryOps.APPEND, null, Map.of("n", 0)).ok(Map.of());
                writer.invoke(HistoryOps.APPEND, null, Map.of("n", 1));
            }

            // then
            HistoryView view = HistoryView.read(file);
            assertThat(view.operations()).hasSize(2);
            assertThat(view.unknowns()).singleElement()
                                       .satisfies(operation -> {
                                           assertThat(operation.completion()).isNull();
                                           assertThat(operation.invocation().longValue("n", -1)).isEqualTo(1L);
                                       });
        }

        @Test
        void anIndeterminateCompletionResolvesToUnknownRatherThanToFailure() {
            // given
            Path file = historyFile();

            // when
            try (HistoryRecorder recorder = HistoryRecorder.writingTo(file, HEADER)) {
                recorder.forProcess("p0", null)
                        .invoke(HistoryOps.COMMIT, null, Map.of())
                        .indeterminate("TimeoutException", Map.of());
            }

            // then
            assertThat(HistoryView.read(file).operations()).singleElement()
                                                           .extracting(Operation::outcome)
                                                           .isEqualTo(Outcome.UNKNOWN);
        }

        @Test
        void trailingOperationsAreNeverTruncated() {
            // given a run whose last three operations are all still open
            Path file = historyFile();

            // when
            try (HistoryRecorder recorder = HistoryRecorder.writingTo(file, HEADER)) {
                HistoryRecorder.ProcessRecorder writer = recorder.forProcess("p0", null);
                writer.invoke(HistoryOps.APPEND, null, Map.of("n", 0)).ok(Map.of());
                writer.invoke(HistoryOps.APPEND, null, Map.of("n", 1));
                writer.invoke(HistoryOps.APPEND, null, Map.of("n", 2));
                writer.invoke(HistoryOps.APPEND, null, Map.of("n", 3));
            }

            // then
            HistoryView view = HistoryView.read(file);
            assertThat(view.operations()).hasSize(4);
            assertThat(view.unknowns()).hasSize(3);
        }

        @Test
        void completionsArrivingOutOfOrderStillPairWithTheirOwnInvocation() {
            // given two overlapping operations completing in reverse order
            Path file = historyFile();

            // when
            try (HistoryRecorder recorder = HistoryRecorder.writingTo(file, HEADER)) {
                HistoryRecorder.ProcessRecorder writer = recorder.forProcess("p0", null);
                HistoryRecorder.Invocation first = writer.invoke(HistoryOps.APPEND, null, Map.of("n", 0));
                HistoryRecorder.Invocation second = writer.invoke(HistoryOps.APPEND, null, Map.of("n", 1));
                second.ok(Map.of("who", "second"));
                first.fail("Rejected", Map.of("who", "first"));
            }

            // then
            List<Operation> operations = HistoryView.read(file).operations();
            assertThat(operations).hasSize(2);
            assertThat(operations.get(0).invocation().longValue("n", -1)).isEqualTo(0L);
            assertThat(operations.get(0).outcome()).isEqualTo(Outcome.FAIL);
            assertThat(operations.get(0).completion()).isNotNull()
                                                      .satisfies(record -> assertThat(record.stringValue("who"))
                                                              .isEqualTo("first"));
            assertThat(operations.get(1).outcome()).isEqualTo(Outcome.OK);
            assertThat(operations.get(1).completion()).isNotNull()
                                                      .satisfies(record -> assertThat(record.stringValue("who"))
                                                              .isEqualTo("second"));
        }

        @Test
        void aStandaloneNoteIsReportedSeparatelyAndNotMistakenForAnOperation() {
            // given
            Path file = historyFile();

            // when
            try (HistoryRecorder recorder = HistoryRecorder.writingTo(file, HEADER)) {
                HistoryRecorder.ProcessRecorder writer = recorder.forProcess("p0", null);
                writer.invoke(HistoryOps.APPEND, null, Map.of()).ok(Map.of());
                writer.info(HistoryOps.SCAN, null, Map.of("eventIds", List.of("e-1", "e-2")));
            }

            // then
            HistoryView view = HistoryView.read(file);
            assertThat(view.operations()).hasSize(1);
            assertThat(view.notes(HistoryOps.SCAN)).singleElement()
                                                   .satisfies(note -> assertThat(note.stringListValue("eventIds"))
                                                           .containsExactly("e-1", "e-2"));
        }

        @Test
        void aCompletionWithoutAnInvocationIsSurfacedRatherThanDiscarded() {
            // given a history damaged so that a completion has no invocation
            List<String> lines = List.of(
                    "{\"schemaVersion\":1,\"scenarioId\":\"damaged\",\"seed\":1,\"backend\":\"in-memory\","
                            + "\"timescale\":\"compressed\",\"workloadShape\":{}}",
                    "{\"idx\":0,\"logicalTs\":0,\"wallTs\":0,\"process\":\"p0\",\"op\":\"append\",\"type\":\"OK\","
                            + "\"id\":\"op-99\",\"value\":{}}"
            );

            // when
            HistoryView view = HistoryView.of(lines);

            // then
            assertThat(view.operations()).isEmpty();
            assertThat(view.unpairedCompletions()).singleElement()
                                                  .extracting(HistoryRecord::id)
                                                  .isEqualTo("op-99");
        }

        @Test
        void recordsAreOrderedByIndexRatherThanByPositionInTheFile() {
            // given lines written out of index order
            List<String> lines = List.of(
                    "{\"schemaVersion\":1,\"scenarioId\":\"reordered\",\"seed\":1,\"backend\":\"in-memory\","
                            + "\"timescale\":\"compressed\",\"workloadShape\":{}}",
                    "{\"idx\":5,\"logicalTs\":0,\"wallTs\":0,\"process\":\"p0\",\"op\":\"append\","
                            + "\"type\":\"INVOKE\",\"id\":\"op-5\",\"value\":{}}",
                    "{\"idx\":1,\"logicalTs\":0,\"wallTs\":0,\"process\":\"p0\",\"op\":\"append\","
                            + "\"type\":\"INVOKE\",\"id\":\"op-1\",\"value\":{}}"
            );

            // when
            HistoryView view = HistoryView.of(lines);

            // then
            assertThat(view.records()).extracting(HistoryRecord::idx).containsExactly(1L, 5L);
        }

        @Test
        void anEmptyHistoryIsRejectedRatherThanReadAsAnEmptyRun() {
            // given / when / then
            assertThatThrownBy(() -> HistoryView.of(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("header");
        }
    }

    @Nested
    class OnDiskFormat {

        @Test
        void everyRecordIsOneLineWithTheHeaderFirst() throws Exception {
            // given
            Path file = historyFile();

            // when
            try (HistoryRecorder recorder = HistoryRecorder.writingTo(file, HEADER)) {
                recorder.forProcess("p0", null).invoke(HistoryOps.APPEND, null, Map.of()).ok(Map.of());
            }

            // then
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(lines).hasSize(3);
            assertThat(lines.getFirst()).contains("\"schemaVersion\":1").contains("\"scenarioId\":\"round-trip\"");
            assertThat(lines.get(1)).contains("\"type\":\"INVOKE\"");
            assertThat(lines.get(2)).contains("\"type\":\"OK\"");
        }

        @Test
        void unknownPropertiesAreIgnoredSoAnOlderHistoryStaysReadable() {
            // given a record carrying a field this build does not know
            List<String> lines = List.of(
                    "{\"schemaVersion\":1,\"scenarioId\":\"future\",\"seed\":1,\"backend\":\"in-memory\","
                            + "\"timescale\":\"compressed\",\"workloadShape\":{},\"newHeaderField\":\"x\"}",
                    "{\"idx\":0,\"logicalTs\":0,\"wallTs\":0,\"process\":\"p0\",\"op\":\"append\","
                            + "\"type\":\"INVOKE\",\"id\":\"op-0\",\"value\":{},\"newRecordField\":42}"
            );

            // when
            HistoryView view = HistoryView.of(lines);

            // then
            assertThat(view.header().scenarioId()).isEqualTo("future");
            assertThat(view.operations()).hasSize(1);
        }
    }
}
