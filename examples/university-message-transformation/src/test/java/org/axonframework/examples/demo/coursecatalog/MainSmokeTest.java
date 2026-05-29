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

package org.axonframework.examples.demo.coursecatalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * End-to-end smoke test: invokes {@link CourseCatalogApplication#main(String[])} and
 * asserts that the seed → command dispatch → projection catch-up → view print →
 * shutdown sequence completes without surfacing an exception.
 */
class MainSmokeTest {

    @Test
    void mainRunsToCompletionWithoutThrowing() {
        assertThatCode(() -> CourseCatalogApplication.main(new String[0]))
                .doesNotThrowAnyException();
    }
}
