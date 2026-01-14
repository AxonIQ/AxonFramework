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

package org.axonframework.extension.micronaut;

import org.axonframework.axonserver.connector.TagsConfiguration;
import org.axonframework.extension.micronaut.util.GrpcServerStub;
import org.axonframework.extension.micronaut.util.TcpUtils;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests whether gathering of Axon tags is properly configured.
 *
 * @author Milan Savic
 */
@Disabled("TODO #3496")
class AxonAutoConfigurationWithTagsTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner();
    }

    @BeforeAll
    static void beforeAll() {
        System.setProperty("axon.axonserver.servers", GrpcServerStub.DEFAULT_HOST + ":" + TcpUtils.findFreePort());
    }

    @AfterAll
    static void afterAll() {
        System.clearProperty("axon.axonserver.servers");
    }

    @Test
    void tagPropertiesAreCapturedInTagConfiguration() {
        testContext.withUserConfiguration(TestContext.class)
                   .withPropertyValues(
                           "axon.tags.region=Eu",
                           "axon.tags.country=It",
                           "axon.tags.city=Rome"
                   )
                   .run(context -> {
                       TagsConfiguration tagsConfiguration = context.getBean(TagsConfiguration.class);
                       assertNotNull(tagsConfiguration);
                       Map<String, String> tags = tagsConfiguration.getTags();
                       assertEquals(3, tags.size());
                       assertEquals("Eu", tags.get("region"));
                       assertEquals("It", tags.get("country"));
                       assertEquals("Rome", tags.get("city"));
                   });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    private static class TestContext {

        @Bean(initMethod = "start", destroyMethod = "shutdown")
        public GrpcServerStub grpcServerStub(@Value("${axon.axonserver.servers}") String servers) {
            return new GrpcServerStub(Integer.parseInt(servers.split(":")[1]));
        }
    }
}
