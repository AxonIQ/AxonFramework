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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point shared by the {@code portal}, {@code courses}, and {@code discovery-server} profiles of this example.
 */
@SpringBootApplication
@EnableScheduling
public class UniversityApplication {

    private final ApplicationEventPublisher publisher;
    private final AtomicLong heartbeatValue = new AtomicLong();

    UniversityApplication(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public static void main(String[] args) {
        SpringApplication.run(UniversityApplication.class, args);
    }

    /**
     * Republishes a {@link HeartbeatEvent} every second on top of Eureka's own registry refresh.
     * <p>
     * This is not required boilerplate for a production application: it exists only to make this example converge
     * quickly for a reader running it locally, by forcing the Spring Cloud connector to re-evaluate discovery-client
     * capabilities faster than Eureka's own {@code registry-fetch-interval-seconds} would on its own.
     */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    void publishDiscoveryHeartbeat() {
        publisher.publishEvent(new HeartbeatEvent(this, heartbeatValue.incrementAndGet()));
    }
}
