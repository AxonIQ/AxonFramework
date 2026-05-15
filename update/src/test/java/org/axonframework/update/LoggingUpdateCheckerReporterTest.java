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

package org.axonframework.update;

import org.axonframework.update.api.Artifact;
import org.axonframework.update.api.UpdateCheckRequest;
import org.axonframework.update.api.UpdateCheckResponse;
import org.axonframework.update.api.ArtifactAvailableUpgrade;
import org.axonframework.update.api.DetectedVulnerability;
import org.axonframework.update.api.DetectedVulnerabilitySeverity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.junit.jupiter.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoggingUpdateCheckerReporterTest {

    private LoggingUpdateCheckerReporter reporter;
    private Logger originalLogger;
    private Logger mockLogger;

    private final UpdateCheckRequest sampleRequest = new UpdateCheckRequest(
            "my-machine-id-1234",
            "my-instance-id-5678",
            "Linux",
            "6",
            "x64",
            "21.0.2",
            "Oracle",
            "none",
            List.of(
                    new Artifact("org.axonframework", "axon-framework", "5.0.1"),
                    new Artifact("org.axonframework", "axon-messaging", "5.0.0"),
                    new Artifact("io.axoniq.console", "framework-client-spring-boot-starter", "2.1.10")
            )
    );

    @BeforeEach
    void setUp() throws Exception {
        reporter = new LoggingUpdateCheckerReporter();

        // Get and store the original logger
        Field loggerField = LoggingUpdateCheckerReporter.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        originalLogger = (Logger) loggerField.get(null);

        // Create and set the spied logger
        mockLogger = spy(LoggerFactory.getLogger(LoggingUpdateCheckerReporter.class));
        loggerField.set(null, mockLogger);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Restore the original logger
        Field loggerField = LoggingUpdateCheckerReporter.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(null, originalLogger);
    }

    @Test
    void logsCorrectlyWithOnlyUpgrades() {
        UpdateCheckResponse updateCheckResponse = new UpdateCheckResponse(500, List.of(
                new ArtifactAvailableUpgrade("org.axonframework", "axon-framework", "5.2.0"),
                new ArtifactAvailableUpgrade("org.axonframework", "axon-messaging", "5.1.0"),
                new ArtifactAvailableUpgrade("io.axoniq.console", "framework-client-spring-boot-starter", "2.2.0")
        ), List.of());

        reporter.report(sampleRequest, updateCheckResponse);

        verify(mockLogger).info("Axoniq has found the following dependency upgrade(s):");
        verify(mockLogger).info("{} {} -> {}",
                                "org.axonframework:axon-framework........................",
                                "5.0.1 ",
                                "5.2.0");
        verify(mockLogger).info("{} {} -> {}",
                                "org.axonframework:axon-messaging........................",
                                "5.0.0 ",
                                "5.1.0");
        verify(mockLogger).info("{} {} -> {}",
                                "io.axoniq.console:framework-client-spring-boot-starter..",
                                "2.1.10",
                                "2.2.0");
        verify(mockLogger).info("No vulnerabilities have been found in your Axon libraries.");
    }

    @Test
    void logsCorrectlyWithOnlyVulnerabilities() {
        UpdateCheckResponse updateCheckResponse = new UpdateCheckResponse(500, List.of(), List.of(
                new DetectedVulnerability("org.axonframework",
                                          "axon-framework",
                                          DetectedVulnerabilitySeverity.HIGH,
                                          "5.0.1",
                                          "CVE-2025-1234: Security vulnerability in message handling"),
                new DetectedVulnerability("org.axonframework",
                                          "axon-messaging",
                                          DetectedVulnerabilitySeverity.CRITICAL,
                                          "5.0.0",
                                          "CVE-2025-67890: Remote code execution vulnerability"),
                new DetectedVulnerability("io.axoniq.console",
                                          "framework-client-spring-boot-starter",
                                          DetectedVulnerabilitySeverity.MEDIUM,
                                          "2.1.10",
                                          "CVE-2025-54321: Information disclosure vulnerability")
        ));

        reporter.report(sampleRequest, updateCheckResponse);

        verify(mockLogger).error("Axoniq has found the following vulnerabilities in your Axon libraries:");
        verify(mockLogger).error("[{}] {} [Fixed in: {}] Description: {}",
                                 "HIGH    ",
                                 "org.axonframework:axon-framework........................",
                                 "5.0.1 ",
                                 "CVE-2025-1234: Security vulnerability in message handling");
        verify(mockLogger).error("[{}] {} [Fixed in: {}] Description: {}",
                                 "CRITICAL",
                                 "org.axonframework:axon-messaging........................",
                                 "5.0.0 ",
                                 "CVE-2025-67890: Remote code execution vulnerability");
        verify(mockLogger).error("[{}] {} [Fixed in: {}] Description: {}",
                                 "MEDIUM  ",
                                 "io.axoniq.console:framework-client-spring-boot-starter..",
                                 "2.1.10",
                                 "CVE-2025-54321: Information disclosure vulnerability");
    }

    @Test
    void logsCorrectlyWithBothUpgradesAndVulnerabilities() {
        UpdateCheckResponse updateCheckResponse = new UpdateCheckResponse(500, List.of(
                new ArtifactAvailableUpgrade("org.axonframework", "axon-framework", "5.2.0"),
                new ArtifactAvailableUpgrade("org.axonframework", "axon-messaging", "5.1.0")
        ), List.of(
                new DetectedVulnerability("org.axonframework",
                                          "axon-framework",
                                          DetectedVulnerabilitySeverity.HIGH,
                                          "5.0.1",
                                          "CVE-2025-1234: Security vulnerability in message handling")
        ));

        reporter.report(sampleRequest, updateCheckResponse);

        verify(mockLogger).error("Axoniq has found the following vulnerabilities in your Axon libraries:");
        verify(mockLogger).error(
                "[{}] {} [Fixed in: {}] Description: {}",
                "HIGH",
                "org.axonframework:axon-framework..",
                "5.0.1",
                "CVE-2025-1234: Security vulnerability in message handling");
        verify(mockLogger).info("Additionally, Axoniq has found an upgrade(s) for your Axon libraries:");
        verify(mockLogger).info(
                "{} {} -> {}",
                "org.axonframework:axon-framework........................",
                "5.0.1 ",
                "5.2.0"
        );
        verify(mockLogger).info(
                "{} {} -> {}",
                "org.axonframework:axon-messaging........................",
                "5.0.0 ",
                "5.1.0"
        );
    }
}
