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

package org.axonframework.update.api;

import org.axonframework.common.annotation.Internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Represents an UpdateChecker request, including machine and instance identifiers, operating system details, JVM
 * information, Kotlin version, and a list of library versions.
 *
 * @param machineId  The unique identifier for the machine. This is a UUID that is generated and stored in the user's
 *                   home directory.
 * @param instanceId The unique identifier for the instance of the application. This is a UUID that is generated for
 *                   each JVM instance.
 * @param osName     The name of the operating system (e.g., "Linux", "Windows").
 * @param osVersion  The version of the operating system (e.g., "6.11.0-26-generic").
 * @param osArch     The architecture of the operating system (e.g., "amd64", "x86_64").
 * @param jvmVersion The version of the Java Virtual Machine (JVM) (e.g., "17.0.2").
 * @param jvmVendor  The vendor of the JVM (e.g., "AdoptOpenJDK", "Oracle").
 * @param kotlinVersion The version of Kotlin used in the application, or "none" if Kotlin is not used.
 * @param libraries  A list of library versions used in the application, each represented by a {@link Artifact}
 *                   object containing the group ID, artifact ID, and version of the library.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
@Internal
public record UpdateCheckRequest(
        String machineId,
        String instanceId,
        String osName,
        String osVersion,
        String osArch,
        String jvmVersion,
        String jvmVendor,
        String kotlinVersion,
        List<Artifact> libraries
) {
    /**
     * Converts the usage request into a query string format suitable for HTTP requests.
     * All values are properly URL encoded.
     *
     * @return The query string representation of the usage request.
     */
    public String toQueryString() {
        StringBuilder sb = new StringBuilder();
        sb.append("os=").append(encode(osName + "; " + osVersion + "; " + osArch))
          .append("&java=").append(encode(jvmVersion + "; " + jvmVendor))
          .append("&kotlin=").append(encode(kotlinVersion));
        for (Artifact library : libraries) {
            sb.append("&lib-").append(library.shortGroupId()).append(".").append(library.artifactId()).append("=").append(encode(library.version()));
        }
        return sb.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Converts the usage request into a user agent string format.
     *
     * @return The user agent string representation of the usage request.
     */
    public String toUserAgent() {
        String axonBaseVersion = getAxonBaseVersion();
        return String.format(
                "Axoniq UpdateChecker/%s (Java %s %s; %s; %s; %s)",
                axonBaseVersion,
                jvmVersion, jvmVendor, osName, osVersion, osArch
        );
    }

        private String getAxonBaseVersion() {
        return libraries.stream()
                        .filter(a -> a.groupId().equals("org.axonframework"))
                        .filter(a -> a.artifactId().equals("axon-messaging"))
                        .map(Artifact::version)
                        .findFirst()
                        .orElse("4.12.0");
    }
}
