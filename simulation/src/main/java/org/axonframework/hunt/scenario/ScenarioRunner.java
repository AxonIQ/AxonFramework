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

package org.axonframework.hunt.scenario;

import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.hunt.checker.CheckResult;
import org.axonframework.hunt.checker.Checker;
import org.axonframework.hunt.checker.CheckerRegistry;
import org.axonframework.hunt.checker.Violation;
import org.axonframework.hunt.fault.Buggify;
import org.axonframework.hunt.fault.Fault;
import org.axonframework.hunt.fault.FaultEvidence;
import org.axonframework.hunt.fault.FaultWindow;
import org.axonframework.hunt.harness.Deadline;
import org.axonframework.hunt.harness.HuntBackend;
import org.axonframework.hunt.harness.HuntWorld;
import org.axonframework.hunt.history.HistoryHeader;
import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecorder;
import org.axonframework.hunt.history.HistoryView;
import org.axonframework.hunt.model.DcbHistoryCodec;
import org.axonframework.hunt.workload.Workload;
import org.axonframework.hunt.workload.WorkloadContext;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a scenario record end to end and returns a verdict.
 * <p>
 * The runner knows no scenario by name. It sets the system up from the record, drives the load through the record's
 * phases, drains to quiescence, closes the history, runs every registered checker over it, and folds the results into
 * one of three verdicts. Adding a scenario therefore costs a declaration and nothing here.
 * <p>
 * The phase order is the part that must not be reordered. Faults are installed only after warmup, removed before the
 * heal phase, and the oracles run only once the system has gone quiet. Judging a system while it is still being
 * broken produces violations at the run boundary that are artefacts of the run boundary, and they are the most common
 * kind of finding that turns out not to exist.
 * <p>
 * The verdict is three-valued. It is a pass only when every oracle the scenario required is registered, every fault
 * it declared fired, the read side caught up, and nothing was found broken. Anything less is undecided, which is a
 * useful answer; reporting it as a pass is not.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class ScenarioRunner {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);
    private static final String HARNESS = "harness";
    private static final String PHASE = "phase";

    private ScenarioRunner() {
        // Utility class.
    }

    /**
     * Runs one seed of a scenario at one tier.
     *
     * @param scenario         the scenario to run
     * @param tier             the tier whose budget and fault-composition limit apply
     * @param seed             the seed to run
     * @param historyDirectory the directory the run's history is written to
     * @return the verdict, with everything needed to act on it
     */
    public static ScenarioResult run(Scenario scenario, Tier tier, long seed, Path historyDirectory) {
        Objects.requireNonNull(scenario, "The scenario cannot be null.");
        Objects.requireNonNull(tier, "The tier cannot be null.");
        Objects.requireNonNull(historyDirectory, "The historyDirectory cannot be null.");

        TierBudget budget = scenario.budget(tier);
        int declaredConcurrency = scenario.faults().maxConcurrentFaults();
        if (declaredConcurrency > tier.maxConcurrentFaults()) {
            throw new IllegalArgumentException(
                    "The scenario [" + scenario.id() + "] composes " + declaredConcurrency
                            + " simultaneous faults, which the " + tier + " tier caps at "
                            + tier.maxConcurrentFaults() + ".");
        }

        Workload workload = scenario.workload().get();
        HuntBackend backend = HuntBackend.byName(scenario.backend());
        HistoryHeader header = HistoryHeader.of(scenario.id(), seed, scenario.backend(),
                                                scenario.timescale().name(),
                                                shapeOf(scenario, workload, tier, seed, budget));
        Path historyFile = historyDirectory.resolve(scenario.id() + "-" + seed + ".jsonl");
        Deadline deadline = Deadline.in("scenario " + scenario.id() + " seed " + seed, budget.wallBudget());
        Buggify buggify = new Buggify(seed, scenario.buggifyProbability());

        List<String> notes = new ArrayList<>();
        Map<String, Long> faultFires = new LinkedHashMap<>();
        long startedAt = System.nanoTime();

        try (HistoryRecorder recorder = HistoryRecorder.writingTo(historyFile, header)) {
            HistoryRecorder.ProcessRecorder harness = recorder.forProcess(HARNESS, null);
            if (scenario.buggifyProbability() > 0.0) {
                buggify.activate();
            }
            try (HuntWorld world = HuntWorld.start(backend, workload, seed, budget.commands(), recorder, buggify,
                                                   scenario.timescale(), scenario.determinism(), deadline)) {
                drive(scenario, workload, world, harness, recorder, deadline, notes, faultFires);
                harness.info(HistoryOps.SCAN, null, Map.of(DcbHistoryCodec.EVENT_IDS, scan(world)));
                harness.info(HistoryOps.PHASE, null, Map.of(PHASE, "verdict"));
            }
            if (scenario.buggifyProbability() > 0.0) {
                harness.info("buggify", null, Map.copyOf(buggify.deactivate()));
            }
        }

        HistoryView history = HistoryView.read(historyFile);
        List<CheckResult> results = CheckerRegistry.runAll(history);
        List<Violation> violations = results.stream().flatMap(result -> result.violations().stream()).toList();
        results.forEach(result -> notes.addAll(result.notes()));
        notes.addAll(missingOracles(scenario));

        Verdict verdict = violations.isEmpty() ? (notes.isEmpty() ? Verdict.PASS : Verdict.INCONCLUSIVE)
                : Verdict.FAIL;
        return new ScenarioResult(scenario.id(), seed, tier, verdict, violations, List.copyOf(notes),
                                  Map.copyOf(faultFires), results, historyFile,
                                  Duration.ofNanos(System.nanoTime() - startedAt), header.reproduceCommand());
    }

    /**
     * Runs every seed a tier declares and returns one result per seed.
     *
     * @param scenario         the scenario to run
     * @param tier             the tier whose budget applies
     * @param historyDirectory the directory the runs' histories are written to
     * @return one result per seed, in seed order
     */
    public static List<ScenarioResult> runTier(Scenario scenario, Tier tier, Path historyDirectory) {
        return scenario.seeds(tier).stream()
                       .map(seed -> run(scenario, tier, seed, historyDirectory))
                       .toList();
    }

    private static void drive(Scenario scenario,
                              Workload workload,
                              HuntWorld world,
                              HistoryRecorder.ProcessRecorder harness,
                              HistoryRecorder recorder,
                              Deadline deadline,
                              List<String> notes,
                              Map<String, Long> faultFires) {
        WorkloadContext context = world.context();
        AtomicReference<Throwable> workloadFailure = new AtomicReference<>();
        Thread driver = new Thread(() -> {
            try {
                workload.run(context);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                workloadFailure.set(e);
            }
        }, "hunt-workload");
        driver.setDaemon(true);

        harness.info(HistoryOps.PHASE, null, Map.of(PHASE, "warmup",
                                                    "warmupMs", scenario.faults().warmup().toMillis()));
        driver.start();
        sleep(scenario.faults().warmup(), deadline);

        List<FaultEvidence> evidence = runFaultWindows(scenario, world, harness, recorder, deadline);

        harness.info(HistoryOps.PHASE, null, Map.of(PHASE, "heal", "healMs", scenario.faults().heal().toMillis()));
        recorder.faultEpoch(null);
        scenario.faults().declaredFaults().forEach(fault -> fault.deactivate(world));
        world.pauses().resumeAll();
        sleep(scenario.faults().heal(), deadline);

        joinQuietly(driver, deadline);
        Throwable failure = workloadFailure.get();
        if (failure != null) {
            notes.add("The workload failed with " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        if (driver.isAlive()) {
            notes.add("The workload had not finished issuing its commands when the run's budget expired.");
        }

        harness.info(HistoryOps.PHASE, null, Map.of(PHASE, "settle",
                                                    "settleMs", scenario.faults().settle().toMillis()));
        if (!settle(workload, context, scenario.faults().settle(), deadline)) {
            notes.add("The read side had not caught up with the store within the settle budget.");
        }

        evidence.forEach(recorded -> {
            faultFires.put(recorded.kind(), recorded.fires());
            harness.info(HistoryOps.FAULT, null, recorded.asRecordValue());
        });
        workload.recordFinalState(context);
        if (deadline.expired()) {
            notes.add("The run outlived its wall-clock budget of " + deadline.label() + ".");
        }
    }

    private static List<FaultEvidence> runFaultWindows(Scenario scenario,
                                                       HuntWorld world,
                                                       HistoryRecorder.ProcessRecorder harness,
                                                       HistoryRecorder recorder,
                                                       Deadline deadline) {
        List<FaultWindow> windows = scenario.faults().windows().stream()
                                            .sorted(Comparator.comparing(FaultWindow::delay))
                                            .toList();
        List<FaultEvidence> evidence = new ArrayList<>();
        if (windows.isEmpty()) {
            return evidence;
        }
        Set<String> open = new LinkedHashSet<>();
        Duration elapsed = Duration.ZERO;
        List<TimedAction> timeline = new ArrayList<>();
        for (FaultWindow window : windows) {
            Map<Fault, FaultEvidence> perFault = new LinkedHashMap<>();
            window.faults().forEach(fault -> perFault.put(fault, new FaultEvidence(fault.kind(),
                                                                                   fault.parameters())));
            evidence.addAll(perFault.values());
            timeline.add(new TimedAction(window.delay(), () -> {
                open.add(window.id());
                recorder.faultEpoch(String.join("+", open));
                harness.info(HistoryOps.PHASE, null, Map.of(PHASE, "fault-window-open", "window", window.id()));
                perFault.forEach((fault, recorded) -> fault.activate(world, recorded));
            }));
            timeline.add(new TimedAction(window.end(), () -> {
                perFault.keySet().forEach(fault -> fault.deactivate(world));
                open.remove(window.id());
                recorder.faultEpoch(open.isEmpty() ? null : String.join("+", open));
                harness.info(HistoryOps.PHASE, null, Map.of(PHASE, "fault-window-close", "window", window.id()));
            }));
        }
        timeline.sort(Comparator.comparing(TimedAction::at));
        for (TimedAction action : timeline) {
            sleep(action.at().minus(elapsed), deadline);
            elapsed = action.at();
            action.action().run();
        }
        return evidence;
    }

    private static boolean settle(Workload workload, WorkloadContext context, Duration budget, Deadline deadline) {
        Deadline settleDeadline = Deadline.in("settle", min(budget, deadline.remaining()));
        try {
            return settleDeadline.awaitUntil(() -> workload.quiesced(context), POLL_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<String> scan(HuntWorld world) {
        List<String> stored = new ArrayList<>();
        MessageStream<EventMessage> stream =
                world.store().source(SourcingCondition.conditionFor(EventCriteria.havingAnyTag()), null);
        try {
            for (var entry = stream.next(); entry.isPresent(); entry = stream.next()) {
                if (entry.get().getResource(ConsistencyMarker.RESOURCE_KEY) == null) {
                    stored.add(entry.get().message().identifier());
                }
            }
        } finally {
            stream.close();
        }
        return List.copyOf(stored);
    }

    private static List<String> missingOracles(Scenario scenario) {
        Set<String> registered = new LinkedHashSet<>();
        for (Checker checker : CheckerRegistry.discover()) {
            registered.addAll(checker.machineNames());
        }
        return scenario.oracles().stream()
                       .filter(oracle -> !registered.contains(oracle))
                       .map(oracle -> "The scenario requires the oracle [" + oracle
                               + "], which no registered checker enforces.")
                       .toList();
    }

    private static Map<String, String> shapeOf(Scenario scenario,
                                               Workload workload,
                                               Tier tier,
                                               long seed,
                                               TierBudget budget) {
        Map<String, String> shape = new LinkedHashMap<>(workload.describe(seed, budget.commands()));
        shape.putAll(scenario.timescale().describe());
        shape.putAll(scenario.faults().describe());
        shape.put("tier", tier.name());
        shape.put("determinism", scenario.determinism().name());
        shape.put("buggifyProbability", String.valueOf(scenario.buggifyProbability()));
        shape.put("claims", String.join(",", new java.util.TreeSet<>(scenario.claims())));
        shape.put("requiredOracles", String.join(",", new java.util.TreeSet<>(scenario.oracles())));
        return Map.copyOf(shape);
    }

    private static void sleep(Duration duration, Deadline deadline) {
        Duration capped = min(duration, deadline.remaining());
        if (capped.isZero() || capped.isNegative()) {
            return;
        }
        try {
            Thread.sleep(capped.toMillis(), capped.toNanosPart() % 1_000_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread, Deadline deadline) {
        try {
            thread.join(Math.max(1L, deadline.remaining().toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    /**
     * Ensures the history directory exists.
     *
     * @param directory the directory to create
     * @return the directory
     */
    public static Path historyDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create the history directory [" + directory + "].", e);
        }
    }

    private record TimedAction(Duration at, Runnable action) {

    }
}
