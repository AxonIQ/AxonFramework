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

// Tiny JUnit Platform launcher for running a hand-picked set of test classes
// without relying on `junit-platform-console-launcher` (which is not
// reliably published on Maven Central for every minor version, e.g. 1.12.x).
//
// Usage:
//   javac --release <N> -d <out>/runner -cp <test-classpath>:<launcher-jar> RunTests.java
//   java  -cp <out>/runner:<out>/test:<out>/main:<test-classpath>:<launcher-jar> \
//         RunTests <FQTestClass1> [<FQTestClass2> ...]
//
// Where <launcher-jar> is e.g.
//   ~/.m2/repository/org/junit/platform/junit-platform-launcher/<v>/junit-platform-launcher-<v>.jar

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;
import java.util.Arrays;

public class RunTests {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: RunTests <FQTestClass1> [<FQTestClass2> ...]");
            System.exit(2);
        }
        var selectors = Arrays.stream(args)
                              .map(DiscoverySelectors::selectClass)
                              .toArray(org.junit.platform.engine.DiscoverySelector[]::new);
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                                                                          .selectors(selectors)
                                                                          .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter pw = new PrintWriter(System.out);
        summary.printTo(pw);
        summary.printFailuresTo(pw);
        pw.flush();
        if (summary.getTotalFailureCount() > 0) {
            System.exit(1);
        }
    }
}
