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

package org.axonframework.hunt.checker;

import org.axonframework.hunt.history.HistoryHeader;
import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecorder;
import org.axonframework.hunt.history.HistoryView;
import org.axonframework.hunt.model.DcbHistoryCodec;
import org.axonframework.hunt.model.ModelAppendCondition;
import org.axonframework.hunt.model.ModelEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Builds small hand-written histories to test checkers against.
 * <p>
 * Histories go through the real recorder and the real reader, so a checker test also proves the round trip it depends
 * on. Nothing here fabricates a {@code HistoryRecord} directly.
 */
final class SyntheticHistory {

    private final Path file;
    private final HistoryRecorder recorder;
    private final HistoryRecorder.ProcessRecorder writer;

    SyntheticHistory(Path directory, String scenarioId) {
        this.file = directory.resolve(scenarioId + ".jsonl");
        this.recorder = HistoryRecorder.writingTo(
                file, HistoryHeader.of(scenarioId, 7L, "in-memory", "compressed", Map.of()));
        this.writer = recorder.forProcess("p0", "node-a");
    }

    HistoryRecorder.ProcessRecorder writer() {
        return writer;
    }

    /**
     * Records an append that succeeded.
     */
    void appendOk(ModelAppendCondition condition, ModelEvent... events) {
        writer.invoke(HistoryOps.APPEND, null, DcbHistoryCodec.encodeAppend(condition, List.of(events)))
              .ok(Map.of());
    }

    /**
     * Records an append the store rejected as conflicting.
     */
    void appendRejected(ModelAppendCondition condition, ModelEvent... events) {
        writer.invoke(HistoryOps.APPEND, null, DcbHistoryCodec.encodeAppend(condition, List.of(events)))
              .fail("AppendEventsTransactionRejectedException", Map.of());
    }

    /**
     * Records an append whose outcome the client could not determine.
     */
    void appendUnknown(ModelAppendCondition condition, ModelEvent... events) {
        writer.invoke(HistoryOps.APPEND, null, DcbHistoryCodec.encodeAppend(condition, List.of(events)))
              .indeterminate("TimeoutException", Map.of());
    }

    /**
     * Records a commit that made the given events visible.
     */
    void commit(String... eventIds) {
        writer.invoke(HistoryOps.COMMIT, null, Map.of(DcbHistoryCodec.EVENT_IDS, List.of(eventIds))).ok(Map.of());
    }

    /**
     * Records a commit whose outcome the client could not determine.
     */
    void commitUnknown(String... eventIds) {
        writer.invoke(HistoryOps.COMMIT, null, Map.of(DcbHistoryCodec.EVENT_IDS, List.of(eventIds)))
              .indeterminate("TimeoutException", Map.of());
    }

    /**
     * Records a rollback that discarded the given events.
     */
    void rollback(String... eventIds) {
        writer.invoke(HistoryOps.ROLLBACK, null, Map.of(DcbHistoryCodec.EVENT_IDS, List.of(eventIds))).ok(Map.of());
    }

    /**
     * Records the delivery of an event to a handler.
     */
    void deliver(String eventId) {
        writer.invoke(HistoryOps.DELIVER, null, Map.of(DcbHistoryCodec.EVENT_ID, eventId)).ok(Map.of());
    }

    /**
     * Records the delivery of an event under the sequence identifier the framework resolved for it.
     */
    void deliverUnderKey(String eventId, String sequenceKey) {
        writer.invoke(HistoryOps.DELIVER, null,
                      Map.of(DcbHistoryCodec.EVENT_ID, eventId, HistoryOps.SEQUENCE_KEY, sequenceKey))
              .ok(Map.of());
    }

    /**
     * Records an authoritative scan of the store after the run has quiesced.
     */
    void scan(String... eventIds) {
        writer.info(HistoryOps.SCAN, null, Map.of(DcbHistoryCodec.EVENT_IDS, List.of(eventIds)));
    }

    /**
     * Records a ledger transfer that committed.
     */
    void transferOk(String from, String to, long amount) {
        writer.invoke(HistoryOps.TRANSFER, from,
                      Map.of("from", from, "to", to, "amount", amount)).ok(Map.of("committed", true));
    }

    /**
     * Records a ledger transfer the run refused or the store rejected.
     */
    void transferFailed(String from, String to, long amount) {
        writer.invoke(HistoryOps.TRANSFER, from,
                      Map.of("from", from, "to", to, "amount", amount))
              .fail("InsufficientFundsException", Map.of("committed", false));
    }

    /**
     * Records the balance projection as it stood once the run had quiesced.
     */
    void projection(long openingTotal, Map<String, Long> balances) {
        writer.info(HistoryOps.PROJECTION, null,
                    Map.of("balances", balances, "openingTotal", openingTotal));
    }

    /**
     * Records the evidence one injected fault left behind.
     */
    void faultEvidence(String kind, long fires) {
        writer.info(HistoryOps.FAULT, null, Map.of("kind", kind, "fires", fires, "targets", List.of()));
    }

    /**
     * Records that a fault made the store hold something other than what was offered.
     */
    void storePerturbed(String interference) {
        writer.info(HistoryOps.STORE_PERTURBED, null,
                    Map.of("interference", interference, "offered", List.of(), "stored", List.of()));
    }

    /**
     * Closes the recorder and reads the history back.
     */
    HistoryView view() {
        recorder.close();
        return HistoryView.read(file);
    }
}
