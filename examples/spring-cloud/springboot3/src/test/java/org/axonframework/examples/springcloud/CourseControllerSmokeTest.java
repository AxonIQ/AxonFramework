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

package org.axonframework.examples.springcloud;

import org.axonframework.examples.springcloud.domain.Course;
import org.axonframework.examples.springcloud.domain.CreateCourse;
import org.axonframework.examples.springcloud.domain.RenameCourseRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the {@code courses} and {@code portal} profiles together in a single JVM, bypassing Spring Cloud
 * distribution and Eureka entirely, to prove the shared domain module's Spring wiring (command/query dispatch, the
 * REST controller, and the reactivestreams-to-{@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}
 * subscription bridge) actually works against this module's resolved Spring Framework version. Cross-node
 * distribution itself is verified manually via the docker-compose setup described in the module README.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"courses", "portal"})
@TestPropertySource(properties = {
        "axon.springcloud.enabled=false",
        "eureka.client.enabled=false",
        // Both the "courses" and "portal" profiles are active so this one JVM hosts both the handlers and the
        // controller; their own application-{profile}.yml each set university.node-name, with "portal" winning
        // the merge since it is activated last. Pin it explicitly so handledBy reports "courses" as it would when
        // the courses node handles the request in the real, separately-deployed topology.
        "university.node-name=courses"
})
class CourseControllerSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Test
    void createsFindsAndRenamesACourseThroughTheRestEndpoints() {
        String courseId = "axon-5-" + UUID.randomUUID();

        ResponseEntity<Course> created = restTemplate.postForEntity(
                "/courses", new CreateCourse(courseId, "Axon Framework 5"), Course.class
        );
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).isEqualTo(new Course(courseId, "Axon Framework 5", "courses"));

        ResponseEntity<Course> found = restTemplate.getForEntity("/courses/{courseId}", Course.class, courseId);
        assertThat(found.getBody()).isEqualTo(created.getBody());

        restTemplate.put("/courses/{courseId}", new RenameCourseRequest("Axon Framework"), courseId);

        ResponseEntity<Course> renamed = restTemplate.getForEntity("/courses/{courseId}", Course.class, courseId);
        assertThat(renamed.getBody()).isEqualTo(new Course(courseId, "Axon Framework", "courses"));
    }

    @Test
    void subscriptionEndpointStreamsTheInitialCourseView() throws Exception {
        String courseId = "axon-5-" + UUID.randomUUID();
        restTemplate.postForEntity("/courses", new CreateCourse(courseId, "Axon Framework 5"), Course.class);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                                         .uri(URI.create(
                                                 "http://localhost:" + port + "/courses/" + courseId
                                                         + "/subscription"))
                                         .header("Accept", "text/event-stream")
                                         .timeout(Duration.ofSeconds(5))
                                         .GET()
                                         .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String dataLine;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            dataLine = reader.lines()
                             .filter(line -> line.startsWith("data:"))
                             .findFirst()
                             .orElse(null);
        }

        assertThat(dataLine).isNotNull()
                             .contains(courseId)
                             .contains("Axon Framework 5")
                             .contains("\"handledBy\":\"courses\"");
    }
}
