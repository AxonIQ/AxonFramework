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

package org.axonframework.examples.demo.multitenancy.shared.messaging;

import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.CourseFullException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the two ways {@link RemoteExceptions#causedBy} recognizes a failure: the exception travelling as
 * itself (in memory) and the exception reconstructed over Axon Server as a generic failure that only
 * carries the original type name in its message.
 */
class RemoteExceptionsTest {

    @Test
    void recognizesTheExceptionInTheCauseChainByType() {
        Throwable failure = new RuntimeException("wrapper", new CourseFullException("cs-101", 2));

        assertThat(RemoteExceptions.causedBy(failure, CourseFullException.class)).isTrue();
    }

    @Test
    void recognizesTheExceptionReconstructedOverTheWireByName() {
        // Axon Server reconstructs a handler failure as a generic execution exception carrying the
        // original type name in its message rather than as the type itself.
        Throwable reconstructed =
                new RuntimeException("io.axoniq...CommandExecutionException: CourseFullException: course full");

        assertThat(RemoteExceptions.causedBy(reconstructed, CourseFullException.class)).isTrue();
    }

    @Test
    void doesNotRecognizeAnUnrelatedFailure() {
        Throwable unrelated = new RuntimeException("connection reset", new IllegalStateException("boom"));

        assertThat(RemoteExceptions.causedBy(unrelated, CourseFullException.class)).isFalse();
    }
}
