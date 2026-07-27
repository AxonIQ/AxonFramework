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
import org.axonframework.hunt.history.HistoryRecord;

import java.util.List;
import java.util.Objects;

/**
 * One broken invariant, reported with everything needed to act on it.
 * <p>
 * A violation is useless without the records that show it and the command that reproduces it, so both are part of the
 * type rather than left to whoever writes the report.
 *
 * @param machineName       the stable name of the invariant that broke, identical to its name in the invariant
 *                          registry and in any formal specification of it
 * @param statement         the invariant's statement, character-identical to the registry's wording
 * @param detail            what specifically went wrong in this run
 * @param records           the history records that show it
 * @param seed              the seed of the run that produced it
 * @param reproduceCommand  the command that replays that run
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record Violation(String machineName,
                        String statement,
                        String detail,
                        List<HistoryRecord> records,
                        long seed,
                        String reproduceCommand) {

    /**
     * Compact constructor rejecting missing parts and defensively copying the records.
     */
    public Violation {
        Objects.requireNonNull(machineName, "The machineName cannot be null.");
        Objects.requireNonNull(statement, "The statement cannot be null.");
        Objects.requireNonNull(detail, "The detail cannot be null.");
        Objects.requireNonNull(reproduceCommand, "The reproduceCommand cannot be null.");
        records = List.copyOf(Objects.requireNonNull(records, "The records cannot be null."));
    }

    /**
     * Creates a violation, taking the seed and the reproduce command from the run's header.
     *
     * @param machineName the stable name of the invariant that broke
     * @param statement   the invariant's statement, character-identical to the registry's wording
     * @param detail      what specifically went wrong
     * @param records     the history records that show it
     * @param header      the header of the run that produced it
     * @return the violation
     */
    public static Violation of(String machineName,
                               String statement,
                               String detail,
                               List<HistoryRecord> records,
                               HistoryHeader header) {
        Objects.requireNonNull(header, "The header cannot be null.");
        return new Violation(machineName, statement, detail, records, header.seed(), header.reproduceCommand());
    }

    /**
     * Renders the violation as a report line.
     *
     * @return a single-line rendering naming the invariant, what broke, and how to replay it
     */
    @Override
    public String toString() {
        return "[" + machineName + "] " + statement
                + " | broken by: " + detail
                + " | records: " + records.stream().map(record -> "#" + record.idx()).toList()
                + " | seed: " + seed
                + " | reproduce: " + reproduceCommand;
    }
}
