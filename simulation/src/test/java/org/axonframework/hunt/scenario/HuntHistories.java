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

import java.nio.file.Path;

/**
 * Where a test's runs write their histories.
 * <p>
 * Under the build directory rather than a temporary directory on purpose: a run's history is the evidence behind its
 * verdict, and the reproduce command a failure prints is only useful next to the file it was produced from.
 */
final class HuntHistories {

    private HuntHistories() {
    }

    static Path directory(String name) {
        return ScenarioRunner.historyDirectory(Path.of("target", "hunt-histories", name));
    }
}
