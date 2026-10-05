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
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateCheckRequestTest {

    @Nested
    class ToQueryString {

        @Test
        void containsEnvironmentAndLibraryVersions() {
            // given
            UpdateCheckRequest request = requestWithLibraries(List.of(
                    new Artifact("org.axonframework", "axon-core", "5.0.0"),
                    new Artifact("org.axonframework.something", "axon-something", "5.0.0"),
                    new Artifact("org.axonframework.extensions", "axon-ext-bland", "5.0.0"),
                    new Artifact("org.axonframework.extensions.kafka", "axon-ext-kafka", "5.0.0"),
                    new Artifact("io.axoniq", "top-level-axoniq", "5.0.0"),
                    new Artifact("io.axoniq.sub", "sub-level-axoniq", "5.0.0"),
                    new Artifact("org.example", "example-lib", "1.2.3")
            ));

            // when
            String queryString = request.toQueryString(42137, true);

            // then all parameters are present and properly encoded
            assertThat(queryString).contains("os=Linux%3B+6.11.0-26-generic%3B+amd64")
                                   .contains("java=17.0.2%3B+AdoptOpenJDK")
                                   .contains("kotlin=1.8.22")
                                   .contains("lib-fw.axon-core=5.0.0")
                                   .contains("lib-fw.something.axon-something=5.0.0")
                                   .contains("lib-ext.axon-ext-bland=5.0.0")
                                   .contains("lib-ext.kafka.axon-ext-kafka=5.0.0")
                                   .contains("lib-iq.top-level-axoniq=5.0.0")
                                   .contains("lib-iq.sub.sub-level-axoniq=5.0.0")
                                   .contains("lib-org.example.example-lib=1.2.3");
        }

        @Test
        void containsIdentifiersAndRuntimeValues() {
            // given
            UpdateCheckRequest request = requestForUserName("machine-user-name");

            // when
            String queryString = request.toQueryString(42137, true);

            // then
            assertThat(queryString).contains("machine-id=machine-1234")
                                   .contains("machine-user-name=machine-user-name")
                                   .contains("instance-id=instance-5678")
                                   .contains("uptime=42137")
                                   .contains("first-run=true");
        }

        @Test
        void firstRunReflectsTheGivenFlag() {
            // given
            UpdateCheckRequest request = requestForUserName("machine-user-name");

            // when
            String queryString = request.toQueryString(42137, false);

            // then
            assertThat(queryString).contains("first-run=false");
        }

        @Test
        void percentEncodesNonAsciiMachineUserName() {
            // given / when / then
            assertThat(queryStringForUserName("\u674E\u96F7")).contains("machine-user-name=%E6%9D%8E%E9%9B%B7");
            assertThat(queryStringForUserName("j\u00F3zef")).contains("machine-user-name=j%C3%B3zef");
        }

        @Test
        void encodesSpacesInMachineUserName() {
            // given / when / then
            assertThat(queryStringForUserName("John Doe")).contains("machine-user-name=John+Doe");
        }

        @Test
        void yieldsAnAsciiOnlyQueryStringForNonAsciiMachineUserName() {
            // a user name outside the ASCII range used to silence the update check entirely, as the value could not
            // be sent as-is; encoding it keeps every character on the wire within ASCII
            // given / when
            String queryString = queryStringForUserName("\u674E\u96F7");

            // then
            assertThat(queryString).matches("\\p{ASCII}+");
        }

        private String queryStringForUserName(String machineUserName) {
            return requestForUserName(machineUserName).toQueryString(42137, true);
        }
    }

    @Nested
    class ToUserAgent {

        @Test
        void describesTheAxonAndRuntimeVersions() {
            // given
            UpdateCheckRequest request = requestWithLibraries(
                    Collections.singletonList(new Artifact("org.axonframework", "axon-messaging", "5.0.1"))
            );

            // when
            String userAgent = request.toUserAgent();

            // then
            assertThat(userAgent).isEqualTo(
                    "Axoniq UpdateChecker/5.0.1 (Java 17.0.2 AdoptOpenJDK; Linux; 6.11.0-26-generic; amd64)"
            );
        }
    }

    private static UpdateCheckRequest requestForUserName(String machineUserName) {
        return new UpdateCheckRequest("machine-1234",
                                      machineUserName,
                                      "instance-5678",
                                      "Linux",
                                      "6.11.0-26-generic",
                                      "amd64",
                                      "17.0.2",
                                      "AdoptOpenJDK",
                                      "1.8.22",
                                      Collections.emptyList());
    }

    private static UpdateCheckRequest requestWithLibraries(List<Artifact> libraries) {
        return new UpdateCheckRequest("machine-1234",
                                      "machine-user-name",
                                      "instance-5678",
                                      "Linux",
                                      "6.11.0-26-generic",
                                      "amd64",
                                      "17.0.2",
                                      "AdoptOpenJDK",
                                      "1.8.22",
                                      libraries);
    }
}
