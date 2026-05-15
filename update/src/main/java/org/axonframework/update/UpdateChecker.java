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

import org.axonframework.common.ObjectUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.update.api.UpdateCheckRequest;
import org.axonframework.update.api.UpdateCheckResponse;
import org.axonframework.update.common.DelayedTask;
import org.axonframework.update.configuration.UsagePropertyProvider;
import org.axonframework.update.detection.AxonVersionDetector;
import org.axonframework.update.detection.KotlinVersion;
import org.axonframework.update.detection.MachineId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The UpdateChecker reports Anonymous usage data to Axoniq periodically. In return, it receives information about
 * available upgrades and vulnerabilities in the Axoniq libraries used. These upgrades and vulnerabilities are reported
 * to the configured {@code UpdateCheckerReporter}.
 * <p>
 * The task will not run if the user has opted out of anonymous usage reporting. There are three ways to disable the
 * anonymous usage reporting:
 * <ol>
 *     <li>Set the environment variable {@code AXONIQ_UPDATE_CHECKER_DISABLED=true}.</li>
 *     <li>Run the JVM with {@code -Daxoniq.update-checker.disabled=true}.</li>
 *     <li>Create the file {@code $HOME/.axoniq/update-checker.properties} with content {@code disabled=true}</li>
 * </ol>
 * These methods are listed in order of precedence, meaning that if the environment variable is set, it will take precedence over the JVM property and the file.
 * Explicitly setting the property to {@code disabled=false} in a method of higher precedence will ignore the lower precedence disabled.
 * <p>
 * This class is not intended to be in use during the running of test suites and therefore does not run when it detects one.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
@Internal
public class UpdateChecker implements Runnable {

    private final static Logger logger = LoggerFactory.getLogger(UpdateChecker.class);

    private final UpdateCheckerHttpClient client;
    private final UpdateCheckerReporter reporter;
    private final UsagePropertyProvider usagePropertyProvider;
    private final MachineId machineId;

    private boolean firstRequest = true;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private int errorRetryBackoffFactor = 1;
    private DelayedTask delayedTask;

    /**
     * Creates a new instance of {@code UpdateCheckTask} with the given {@link UpdateCheckerHttpClient} and
     * {@link UsagePropertyProvider}.
     *
     * @param client                The HTTP client used to send requests to the telemetry endpoint.
     * @param reporter              The reporter that will handle the response from the telemetry endpoint.
     * @param usagePropertyProvider The property provider used to determine if the update check is disabled.
     */
    public UpdateChecker(UpdateCheckerHttpClient client,
                         UpdateCheckerReporter reporter,
                         UsagePropertyProvider usagePropertyProvider) {
        this.client = Objects.requireNonNull(client, "The client must not be null.");
        this.reporter = Objects.requireNonNull(reporter, "The reporter must not be null.");
        this.usagePropertyProvider = Objects.requireNonNull(
                usagePropertyProvider, "The usagePropertyProvider must not be null."
        );
        this.machineId = new MachineId();
    }

    /**
     * Starts the anonymous usage reporting task. If the user has opted out of anonymous usage reporting, or a testsuite
     * is detected, the task will not be started.
     */
    public void start() {
        try {
            if (!started.compareAndSet(false, true)) {
                logger.debug("The Axoniq UpdateChecker was already started.");
                return;
            }
            if (ObjectUtils.getOrDefault(usagePropertyProvider.getDisabled(), false)) {
                logger.info(
                        "You have opted out of the Axoniq UpdateChecker. No update or vulnerabilities will be checked. See https://www.axoniq.io/update-check for more information.");
                return;
            }
            logger.info(
                    "Your Axoniq libraries will be checked for update periodically. See https://www.axoniq.io/update-check for more information.");

            delayedTask = DelayedTask.of(this, 1000);
        } catch (Exception e) {
            logger.warn("Failed to start the UpdateChecker task.", e);
        }
    }

    @Override
    public void run() {
        if (!started.get()) {
            return;
        }
        try {
            UpdateCheckRequest requestBody = buildRequest();
            Optional<UpdateCheckResponse> response = client.sendRequest(requestBody, firstRequest);
            if (response.isEmpty()) {
                scheduleErrorRetry();
                return;
            }

            UpdateCheckResponse updateCheckResponse = response.get();
            reporter.report(requestBody, updateCheckResponse);

            logger.debug("Axoniq will check library update and vulnerabilities again in {} seconds.",
                         updateCheckResponse);
            delayedTask = DelayedTask.of(this, updateCheckResponse.checkInterval() * 1000L);
            errorRetryBackoffFactor = 1; // Reset backoff factor on a successful report
            firstRequest = false;
        } catch (Exception e) {
            logger.warn("The Axoniq UpdateChecker failed to fetch update and vulnerabilities.", e);
            scheduleErrorRetry();
        }
    }

    /**
     * Allows the task to be stopped, preventing any further update from being checked. Useful for terminating the task
     * gracefully, for example, when the application is shutting down.
     */
    public void stop() {
        if (started.compareAndSet(true, false)) {
            logger.debug("Stopped the Axoniq UpdateChecker. No further update will be checked.");
            if (this.delayedTask != null) {
                delayedTask.cancel();
                delayedTask = null;
            }
        }
    }

    private void scheduleErrorRetry() {
        errorRetryBackoffFactor++;
        int nextInvocationTime = Math.min((int) ((Math.pow(2, errorRetryBackoffFactor)) * 1000), 60000);
        delayedTask = DelayedTask.of(this, nextInvocationTime);
    }

        private UpdateCheckRequest buildRequest() {
        String jvmVendor = System.getProperty("java.vendor");
        String javaVersion = System.getProperty("java.version");
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        String osVersion = System.getProperty("os.version");
        String installationId = UUID.randomUUID().toString();

        return new UpdateCheckRequest(
                machineId.get(),
                installationId,
                osName,
                osVersion,
                osArch,
                javaVersion,
                jvmVendor,
                KotlinVersion.get(),
                AxonVersionDetector.safeDetectAxonModules()
        );
    }

    /**
     * Checks if the UpdateChecker has been started.
     *
     * @return {@code true} if the UpdateChecker has been started, {@code false} otherwise.
     */
    public boolean isStarted() {
        return started.get();
    }
}
