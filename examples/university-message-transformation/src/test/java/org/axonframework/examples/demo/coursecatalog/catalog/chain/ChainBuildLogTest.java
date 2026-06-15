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

package org.axonframework.examples.demo.coursecatalog.catalog.chain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.axonframework.examples.demo.coursecatalog.catalog.transformations.CourseCatalogTransformations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainBuildLogTest {

    private static final String CHAIN_LOGGER_NAME = "io.axoniq.framework.messaging.transformation.events.EventTransformerChain";

    private Logger chainLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachListAppender() {
        chainLogger = (Logger) LoggerFactory.getLogger(CHAIN_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        chainLogger.addAppender(appender);
        chainLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachListAppender() {
        chainLogger.detachAppender(appender);
    }

    @Test
    void chainBuildEmitsOneInfoEntryNamingEveryRegisteredTransformer() {
        CourseCatalogTransformations.chain();

        List<ILoggingEvent> infoEntries = appender.list.stream()
                                                       .filter(e -> e.getLevel() == Level.INFO)
                                                       .toList();
        assertThat(infoEntries).hasSize(1);
        assertThat(infoEntries.getFirst().getFormattedMessage())
                .contains("6 transformation(s)",
                          "coursecatalog.CoursePublished#1.0.0",
                          "coursecatalog.CoursePublished#2.0.0",
                          "coursecatalog.StudentRegistered#1.0.0",
                          "coursecatalog.StudentRegistered#2.0.0",
                          "coursecatalog.SystemAnnouncement#0.0.1",
                          "coursecatalog.WelcomeMessageSent#1.0.0");
    }
}
