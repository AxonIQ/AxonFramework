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

import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecord;
import org.axonframework.hunt.history.HistoryView;
import org.axonframework.hunt.history.Operation;
import org.axonframework.hunt.history.Outcome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks the ledger's arithmetic: that money is neither created nor destroyed, that no account was ever overdrawn,
 * and that the read model agrees with what the run committed.
 * <p>
 * This checker runs against every history, whatever the scenario was about. A conservation law is the cheapest strong
 * oracle there is, and it catches failure modes nobody enumerated: a lost event, a doubled event, a torn batch and a
 * bypassed conflict check all show up as an arithmetic disagreement, without the suite having had to predict which of
 * them would happen. A history with no ledger in it produces no verdict here and no noise.
 * <p>
 * Two situations downgrade the verdict instead of deciding it. If a fault made the store hold something other than
 * what the workload offered, the missing money is the harness's doing and blaming the framework for it would be a
 * false finding. If any transfer's outcome is unknown, the expected balances are a range rather than a number, and
 * asserting against one point in that range is guesswork.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class ConservationChecker implements Checker {

    /**
     * The stable name of the invariant that the ledger's total never changes.
     */
    public static final String LEDGER_CONSERVES_TOTAL_BALANCE = "LedgerConservesTotalBalance";

    /**
     * The statement of {@link #LEDGER_CONSERVES_TOTAL_BALANCE}, character-identical to the invariant registry.
     */
    public static final String LEDGER_CONSERVES_TOTAL_BALANCE_STATEMENT =
            "The balances the projection reports sum to the ledger's opening total.";

    /**
     * The stable name of the invariant that no account is ever overdrawn.
     */
    public static final String LEDGER_BALANCE_NEVER_NEGATIVE = "LedgerBalanceNeverNegative";

    /**
     * The statement of {@link #LEDGER_BALANCE_NEVER_NEGATIVE}, character-identical to the invariant registry.
     */
    public static final String LEDGER_BALANCE_NEVER_NEGATIVE_STATEMENT =
            "No account balance is negative at any point in the sequence of committed transfers.";

    /**
     * The stable name of the invariant that the read model agrees with the write side.
     */
    public static final String PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS =
            "ProjectionMatchesFoldOfCommittedEvents";

    /**
     * The statement of {@link #PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS}, character-identical to the invariant
     * registry.
     */
    public static final String PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS_STATEMENT =
            "The balance projection at the end of the run equals the fold of the transfers the run committed.";

    private static final String FROM = "from";
    private static final String TO = "to";
    private static final String AMOUNT = "amount";
    private static final String BALANCES = "balances";
    private static final String OPENING_TOTAL = "openingTotal";
    private static final String COMMITTED = "committed";

    @Override
    public String name() {
        return "ConservationChecker";
    }

    @Override
    public Set<String> machineNames() {
        return Set.of(LEDGER_CONSERVES_TOTAL_BALANCE,
                      LEDGER_BALANCE_NEVER_NEGATIVE,
                      PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS);
    }

    @Override
    public CheckResult check(HistoryView history) {
        List<HistoryRecord> projections = history.notes(HistoryOps.PROJECTION);
        if (projections.isEmpty()) {
            return CheckResult.holding(name());
        }
        HistoryRecord projection = projections.getLast();
        Map<String, Long> reported = balancesOf(projection);
        if (reported.isEmpty()) {
            return CheckResult.holding(name());
        }

        List<Violation> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean decidable = true;

        int perturbed = history.notes(HistoryOps.STORE_PERTURBED).size();
        if (perturbed > 0) {
            notes.add("A fault made the store hold something other than what was offered, on " + perturbed
                              + " commit(s); any missing or doubled money is the fault's, not the framework's.");
            decidable = false;
        }
        List<Operation> transfers = history.operations(HistoryOps.TRANSFER);
        long unknown = transfers.stream().filter(transfer -> transfer.outcome() == Outcome.UNKNOWN).count();
        if (unknown > 0) {
            notes.add(unknown + " transfer(s) have an unknown outcome; the expected balances are a range.");
            decidable = false;
        }

        long openingTotal = projection.longValue(OPENING_TOTAL, 0L);
        long openingPerAccount = openingTotal / reported.size();
        long reportedTotal = reported.values().stream().mapToLong(Long::longValue).sum();
        if (reportedTotal != openingTotal) {
            record(decidable, violations, notes, history, projection,
                   LEDGER_CONSERVES_TOTAL_BALANCE, LEDGER_CONSERVES_TOTAL_BALANCE_STATEMENT,
                   "the projection reports a total of " + reportedTotal + " against an opening total of "
                           + openingTotal);
        }

        Map<String, Long> expected = new HashMap<>();
        reported.keySet().forEach(account -> expected.put(account, openingPerAccount));
        for (Operation transfer : transfers) {
            if (transfer.outcome() != Outcome.OK || !committed(transfer)) {
                continue;
            }
            String from = transfer.invocation().stringValue(FROM);
            String to = transfer.invocation().stringValue(TO);
            long amount = transfer.invocation().longValue(AMOUNT, 0L);
            if (from == null || to == null) {
                continue;
            }
            expected.merge(from, -amount, Long::sum);
            expected.merge(to, amount, Long::sum);
            if (expected.get(from) < 0) {
                record(decidable, violations, notes, history, transfer.invocation(),
                       LEDGER_BALANCE_NEVER_NEGATIVE, LEDGER_BALANCE_NEVER_NEGATIVE_STATEMENT,
                       "account [" + from + "] reached " + expected.get(from) + " after a committed transfer of "
                               + amount);
            }
        }

        Map<String, Long> divergent = new TreeMap<>();
        expected.forEach((account, balance) -> {
            long actual = reported.getOrDefault(account, Long.MIN_VALUE);
            if (actual != balance) {
                divergent.put(account, balance);
            }
        });
        if (!divergent.isEmpty()) {
            Map<String, Long> observed = new TreeMap<>();
            divergent.keySet().forEach(account -> observed.put(account, reported.get(account)));
            record(decidable, violations, notes, history, projection,
                   PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS, PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS_STATEMENT,
                   "expected " + divergent + " but the projection reported " + observed);
        }

        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes));
    }

    private static boolean committed(Operation transfer) {
        HistoryRecord completion = transfer.completion();
        return completion == null || !Boolean.FALSE.equals(completion.value().get(COMMITTED));
    }

    private static Map<String, Long> balancesOf(HistoryRecord projection) {
        Object raw = projection.value().get(BALANCES);
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Long> balances = new LinkedHashMap<>();
        map.forEach((account, balance) -> {
            if (balance instanceof Number number) {
                balances.put(String.valueOf(account), number.longValue());
            }
        });
        return Map.copyOf(balances);
    }

    private void record(boolean decidable,
                        List<Violation> violations,
                        List<String> notes,
                        HistoryView history,
                        HistoryRecord evidence,
                        String machineName,
                        String statement,
                        String detail) {
        if (decidable) {
            violations.add(Violation.of(machineName, statement, detail, List.of(evidence), history.header()));
        } else {
            notes.add("Undecidable, because the run was perturbed: [" + machineName + "] " + detail + ".");
        }
    }
}
