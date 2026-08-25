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

package org.axonframework.messaging.unitofwork;

import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.common.util.MockException;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Allard Buijze
 */
class UnitOfWorkNestingTest {

    private final List<PhaseTransition> phaseTransitions = new ArrayList<>();
    private LegacyUnitOfWork<?> outer;
    private LegacyUnitOfWork<?> middle;
    private LegacyUnitOfWork<?> inner;

    @BeforeEach
    void setUp() {
        phaseTransitions.clear();
        while (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().rollback();
        }

        outer = new LegacyDefaultUnitOfWork<>(new GenericEventMessage(new MessageType("event"), "Input 1")) {
            @Override
            public String toString() {
                return "outer";
            }
        };
        middle = new LegacyDefaultUnitOfWork<>(
                new GenericEventMessage(new MessageType("event"), "Input middle")
        ) {
            @Override
            public String toString() {
                return "middle";
            }
        };
        inner = new LegacyDefaultUnitOfWork<>(new GenericEventMessage(new MessageType("event"), "Input 2")) {
            @Override
            public String toString() {
                return "inner";
            }
        };
        registerListeners(outer);
        registerListeners(middle);
        registerListeners(inner);
    }

    private void registerListeners(LegacyUnitOfWork<?> unitOfWork) {
        unitOfWork.onPrepareCommit(
                u -> phaseTransitions.add(new PhaseTransition(u, LegacyUnitOfWork.Phase.PREPARE_COMMIT))
        );
        unitOfWork.onCommit(u -> phaseTransitions.add(new PhaseTransition(u, LegacyUnitOfWork.Phase.COMMIT)));
        unitOfWork.afterCommit(u -> phaseTransitions.add(new PhaseTransition(u, LegacyUnitOfWork.Phase.AFTER_COMMIT)));
        unitOfWork.onRollback(u -> phaseTransitions.add(new PhaseTransition(u, LegacyUnitOfWork.Phase.ROLLBACK)));
        unitOfWork.onCleanup(u -> phaseTransitions.add(new PhaseTransition(u, LegacyUnitOfWork.Phase.CLEANUP)));
    }

    @AfterEach
    void tearDown() {
        assertFalse(CurrentUnitOfWork.isStarted(), "A UnitOfWork was not properly cleared");
    }

    @Test
    void innerUnitOfWorkNotifiedOfOuterCommitFailure() {
        outer.onPrepareCommit(u -> {
            inner.start();
            inner.commit();
        });
        outer.onCommit(u -> {
            throw new MockException();
        });
        outer.onCommit(u -> phaseTransitions.add(new PhaseTransition(u, LegacyUnitOfWork.Phase.COMMIT, "x")));
        outer.start();
        try {
            outer.commit();
        } catch (MockException e) {
            //ok
        }

        assertFalse(CurrentUnitOfWork.isStarted(), "The UnitOfWork hasn't been correctly cleared");
        assertEquals(Arrays.asList(new PhaseTransition(outer, LegacyUnitOfWork.Phase.PREPARE_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.PREPARE_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.COMMIT),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.COMMIT, "x"),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.ROLLBACK),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.ROLLBACK),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.CLEANUP),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.CLEANUP)
        ), phaseTransitions);
    }

    @Test
    void innerUnitOfWorkNotifiedOfOuterPrepareCommitFailure() {
        outer.onPrepareCommit(u -> {
            inner.start();
            inner.commit();
        });
        outer.onPrepareCommit(u -> {
            throw new MockException();
        });
        outer.start();
        try {
            outer.commit();
        } catch (MockException e) {
            //ok
        }

        assertFalse(CurrentUnitOfWork.isStarted(), "The UnitOfWork hasn't been correctly cleared");
        assertEquals(Arrays.asList(new PhaseTransition(outer, LegacyUnitOfWork.Phase.PREPARE_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.PREPARE_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.ROLLBACK),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.ROLLBACK),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.CLEANUP),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.CLEANUP)
        ), phaseTransitions);
    }

    @Test
    void innerUnitOfWorkNotifiedOfOuterCommit() {
        outer.onPrepareCommit(u -> {
            inner.start();
            inner.commit();
        });
        outer.start();
        outer.commit();

        assertFalse(CurrentUnitOfWork.isStarted(), "The UnitOfWork hasn't been correctly cleared");
        assertEquals(Arrays.asList(new PhaseTransition(outer, LegacyUnitOfWork.Phase.PREPARE_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.PREPARE_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.COMMIT),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.AFTER_COMMIT),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.AFTER_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.CLEANUP),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.CLEANUP)
        ), phaseTransitions);
    }

    @Test
    void innerUnitRollbackDoesNotAffectOuterCommit() {
        outer.onPrepareCommit(u -> {
            inner.start();
            inner.rollback(new MockException());
        });
        outer.start();
        outer.commit();

        assertFalse(CurrentUnitOfWork.isStarted(), "The UnitOfWork hasn't been correctly cleared");
        assertEquals(Arrays.asList(new PhaseTransition(outer, LegacyUnitOfWork.Phase.PREPARE_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.ROLLBACK),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.COMMIT),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.AFTER_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.CLEANUP),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.CLEANUP)
        ), phaseTransitions);
    }

    @Test
    void rollbackOfMiddleUnitOfWorkRollsBackInner() {
        outer.onPrepareCommit(u -> {
            middle.start();
            inner.start();
            inner.commit();
            middle.rollback();
        });
        outer.start();
        outer.commit();

        assertTrue(middle.isRolledBack(), "The middle UnitOfWork hasn't been correctly marked as rolled back");
        assertTrue(inner.isRolledBack(), "The inner UnitOfWork hasn't been correctly marked as rolled back");
        assertFalse(outer.isRolledBack(), "The out UnitOfWork has been incorrectly marked as rolled back");
        assertFalse(CurrentUnitOfWork.isStarted(), "The UnitOfWork hasn't been correctly cleared");
        assertEquals(Arrays.asList(new PhaseTransition(outer, LegacyUnitOfWork.Phase.PREPARE_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.PREPARE_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.COMMIT),
                                   // important that the inner has been given a rollback signal
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.ROLLBACK),
                                   new PhaseTransition(middle, LegacyUnitOfWork.Phase.ROLLBACK),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.COMMIT),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.AFTER_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.CLEANUP),
                                   new PhaseTransition(middle, LegacyUnitOfWork.Phase.CLEANUP),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.CLEANUP)
        ), phaseTransitions);
    }

    @Test
    void innerUnitCommitFailureDoesNotAffectOuterCommit() {
        outer.onPrepareCommit(u -> {
            inner.start();
            inner.onCommit(uow -> {
                throw new MockException();
            });
            // commits are invoked in reverse order, so we expect to see this one, but not the previously registered handler
            inner.onCommit(uow -> phaseTransitions.add(new PhaseTransition(inner, LegacyUnitOfWork.Phase.COMMIT, "x")));
            try {
                inner.commit();
            } catch (MockException e) {
                //ok
            }
        });
        outer.start();
        outer.commit();

        assertFalse(CurrentUnitOfWork.isStarted(), "The UnitOfWork hasn't been correctly cleared");
        assertEquals(Arrays.asList(new PhaseTransition(outer, LegacyUnitOfWork.Phase.PREPARE_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.PREPARE_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.COMMIT, "x"),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.ROLLBACK),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.COMMIT),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.AFTER_COMMIT),
                                   new PhaseTransition(inner, LegacyUnitOfWork.Phase.CLEANUP),
                                   new PhaseTransition(outer, LegacyUnitOfWork.Phase.CLEANUP)
        ), phaseTransitions);
    }

    private static class PhaseTransition {

        private final LegacyUnitOfWork.Phase phase;
        private final LegacyUnitOfWork<?> unitOfWork;
        private final String id;

        public PhaseTransition(LegacyUnitOfWork<?> unitOfWork, LegacyUnitOfWork.Phase phase) {
            this(unitOfWork, phase, "");
        }

        public PhaseTransition(LegacyUnitOfWork<?> unitOfWork, LegacyUnitOfWork.Phase phase, String id) {
            this.unitOfWork = unitOfWork;
            this.phase = phase;
            this.id = id.length() > 0 ? " " + id : id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            PhaseTransition that = (PhaseTransition) o;
            return Objects.equals(phase, that.phase) &&
                    Objects.equals(unitOfWork, that.unitOfWork) &&
                    Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(phase, unitOfWork, id);
        }

        @Override
        public String toString() {
            return unitOfWork + " " + phase + id;
        }
    }
}
