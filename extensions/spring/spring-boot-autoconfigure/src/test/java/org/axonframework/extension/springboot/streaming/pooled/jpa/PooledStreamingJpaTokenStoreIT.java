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

package org.axonframework.extension.springboot.streaming.pooled.jpa;

import org.axonframework.extension.springboot.streaming.pooled.PooledStreamingEventProcessorSpringTestSuite;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;

/**
 * Spring Boot integration test for {@link org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor}
 * backed by a {@link org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore}.
 * <p>
 * Full JPA autoconfiguration is used: HSQLDB in-memory provides both the JPA event store and the JPA token store.
 * The token table is created by Hibernate via {@code ddl-auto=create-drop}.
 * <p>
 * Each test creates its own processor with a UUID-based name, giving natural token store row isolation without any
 * schema teardown between tests.
 *
 * @since 5.1.1
 */
@SpringBootTest(
        properties = {
                "axon.axonserver.enabled=false",
                "spring.main.banner-mode=off",
                "spring.datasource.generate-unique-name=true",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "axon.eventstorage.jpa.polling-interval=0"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
public class PooledStreamingJpaTokenStoreIT extends PooledStreamingEventProcessorSpringTestSuite {

}
