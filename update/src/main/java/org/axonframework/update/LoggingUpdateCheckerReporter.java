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
import org.axonframework.update.api.DetectedVulnerability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

/**
 * An {@link UpdateCheckerReporter} implementation that logs the results of the update check.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class LoggingUpdateCheckerReporter implements UpdateCheckerReporter {

    @SuppressWarnings("FieldMayBeFinal") // For testing purposes, we need to be able to mock this logger
    private static Logger logger = LoggerFactory.getLogger(LoggingUpdateCheckerReporter.class);

    @Override
    public void report(UpdateCheckRequest request, UpdateCheckResponse updateCheckResponse) {

        boolean hasUpgrades = !updateCheckResponse.upgrades().isEmpty();
        boolean hasVulnerabilities = !updateCheckResponse.vulnerabilities().isEmpty();
        if (hasVulnerabilities) {
            logger.error("Axoniq has found the following vulnerabilities in your Axon libraries:");
            logVulnerabilities(updateCheckResponse);

            if (hasUpgrades) {
                logger.info("Additionally, Axoniq has found an upgrade(s) for your Axon libraries:");
                logUpgrades(request, updateCheckResponse);
            }
        } else if (hasUpgrades) {
            logger.info("Axoniq has found the following dependency upgrade(s):");
            logUpgrades(request, updateCheckResponse);
            logger.info("No vulnerabilities have been found in your Axon libraries.");
        } else {
            logger.info("All your Axoniq libraries are up-to-date, no upgrades or vulnerabilities found.");
        }
    }

    private void logUpgrades(UpdateCheckRequest requestBody, UpdateCheckResponse updateCheckResponse) {
        int nameLength = calculateStringLength(requestBody.libraries(),
                                               upgrade -> upgrade.groupId() + ":" + upgrade.artifactId()) + 2;
        int versionLength = calculateStringLength(requestBody.libraries(), Artifact::version);

        updateCheckResponse.upgrades().forEach(upgrade -> {
            String currentVersion = getCurrentVersionForArtifact(requestBody, upgrade.groupId(), upgrade.artifactId());
            logger.info("{} {} -> {}",
                        rightPad(upgrade.groupId() + ":" + upgrade.artifactId(), nameLength, '.'),
                        rightPad(currentVersion, versionLength, ' '),
                        upgrade.latestVersion()
            );
        });
    }

    static String getCurrentVersionForArtifact(UpdateCheckRequest requestBody, String groupId, String artifactId) {
        return requestBody.libraries().stream()
                          .filter(v -> v.artifactId().equals(artifactId) && v.groupId().equals(groupId))
                          .findFirst()
                          .map(Artifact::version)
                          .orElse("unknown");
    }

    private <T> int calculateStringLength(List<T> items, Function<T, String> stringExtractor) {
        return items.stream()
                    .map(stringExtractor)
                    .mapToInt(String::length)
                    .max()
                    .orElse(0);
    }

    private String rightPad(String str, int length, char padChar) {
        if (str.length() >= length) {
            return str;
        }
        String padCharString = String.valueOf(padChar);
        return str + padCharString.repeat(length - str.length());
    }

    private void logVulnerabilities(UpdateCheckResponse updateCheckResponse) {
        int nameLength = calculateStringLength(updateCheckResponse.vulnerabilities(),
                                               upgrade -> upgrade.groupId() + ":" + upgrade.artifactId()) + 2;

        int severityLength = calculateStringLength(updateCheckResponse.vulnerabilities(),
                                                   vulnerability -> vulnerability.severity().toString());

        int fixVersionLength = calculateStringLength(updateCheckResponse.vulnerabilities(),
                                                     DetectedVulnerability::fixVersion);

        updateCheckResponse.vulnerabilities().forEach(vulnerability -> {
            logger.error("[{}] {} [Fixed in: {}] Description: {}",
                         rightPad(vulnerability.severity().toString(), severityLength, ' '),
                         rightPad(vulnerability.groupId() + ":" + vulnerability.artifactId(), nameLength, '.'),
                         rightPad(vulnerability.fixVersion(), fixVersionLength, ' '),
                         vulnerability.description()
            );
        });
    }
}
