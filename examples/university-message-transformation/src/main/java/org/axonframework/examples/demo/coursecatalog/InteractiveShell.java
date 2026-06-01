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

package org.axonframework.examples.demo.coursecatalog;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.CatalogViewReadModel;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.CourseCatalogView;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.GetCourseCatalogView;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.catalog.write.enrollstudent.EnrollStudent;
import org.axonframework.examples.demo.coursecatalog.catalog.write.publishcourse.PublishCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.write.updatecoursecapacity.UpdateCourseCapacity;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tiny stdin REPL that lets a user keep the demo application running and send
 * commands to it. Started by {@link CourseCatalogApplication} when the
 * {@code --keep-alive} program argument is set. Exits on {@code exit}, {@code quit},
 * or EOF (Ctrl+D), after which {@code CourseCatalogApplication#main} runs its
 * normal {@code finally} block and shuts the configuration down.
 */
final class InteractiveShell {

    private static final Logger logger = LoggerFactory.getLogger(InteractiveShell.class);

    private InteractiveShell() {
    }

    static void run(AxonConfiguration configuration) {
        printBanner();
        CommandGateway commands = configuration.getComponent(CommandGateway.class);
        QueryGateway queries = configuration.getComponent(QueryGateway.class);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("course-catalog> ");
            System.out.flush();
            String line;
            try {
                line = reader.readLine();
            } catch (java.io.IOException e) {
                logger.warn("Stdin read failed, exiting shell: {}", e.getMessage());
                return;
            }
            if (line == null) {
                // EOF (Ctrl+D)
                System.out.println();
                return;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                if (!handle(trimmed, commands, queries)) {
                    return;
                }
            } catch (RuntimeException e) {
                System.out.println("[error] " + e.getMessage());
            }
        }
    }

    /** @return false to exit the shell, true to keep going */
    private static boolean handle(String line, CommandGateway commands, QueryGateway queries) {
        List<String> tokens = tokenize(line);
        String head = tokens.get(0);
        switch (head) {
            case "help":
            case "?":
                printHelp();
                return true;
            case "view":
                printView(queries);
                return true;
            case "publish":
                require(tokens, 5, "publish <courseId> \"<name>\" <min> <max>");
                commands.sendAndWait(new PublishCourse(
                        CourseId.of(tokens.get(1)),
                        tokens.get(2),
                        new CapacityRange(Integer.parseInt(tokens.get(3)), Integer.parseInt(tokens.get(4)))));
                System.out.println("[ok] published " + tokens.get(1));
                return true;
            case "capacity":
                require(tokens, 4, "capacity <courseId> <min> <max>");
                commands.sendAndWait(new UpdateCourseCapacity(
                        CourseId.of(tokens.get(1)),
                        new CapacityRange(Integer.parseInt(tokens.get(2)), Integer.parseInt(tokens.get(3)))));
                System.out.println("[ok] capacity updated for " + tokens.get(1));
                return true;
            case "enroll":
                require(tokens, 3, "enroll <courseId> <studentId>");
                commands.sendAndWait(new EnrollStudent(CourseId.of(tokens.get(1)), StudentId.of(tokens.get(2))));
                System.out.println("[ok] enrolled " + tokens.get(2) + " in " + tokens.get(1));
                return true;
            case "exit":
            case "quit":
                return false;
            default:
                System.out.println("[error] unknown command '" + head + "'. Type 'help' for the list.");
                return true;
        }
    }

    private static void require(List<String> tokens, int expected, String usage) {
        if (tokens.size() != expected) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("Interactive shell ready. Type 'help' for available commands, 'exit' to shut down.");
    }

    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  publish  <courseId> \"<name>\" <min> <max>   Publish a new course");
        System.out.println("  capacity <courseId> <min> <max>             Update a course's capacity range");
        System.out.println("  enroll   <courseId> <studentId>             Enroll a student in a course");
        System.out.println("  view                                        Print the catalog view");
        System.out.println("  help                                        Show this message");
        System.out.println("  exit                                        Shut down");
    }

    private static void printView(QueryGateway queries) {
        CourseCatalogView view = queries.query(new GetCourseCatalogView(), CourseCatalogView.class, null)
                                        .orTimeout(5, TimeUnit.SECONDS)
                                        .join();
        System.out.println("Registered students: " + view.registeredStudents());
        System.out.println("Courses (" + view.courses().size() + "):");
        for (CatalogViewReadModel course : view.courses()) {
            System.out.println("  - " + course.courseId()
                                       + " \"" + course.name() + "\""
                                       + " range=" + course.range()
                                       + " enrolments=" + course.enrolments()
                                       + (course.registrationClosed() ? " [closed]" : ""));
        }
        System.out.println("Announcements (" + view.announcements().size() + "):");
        for (String announcement : view.announcements()) {
            System.out.println("  - " + announcement);
        }
    }

    /**
     * Splits a line on whitespace while preserving double-quoted segments as a
     * single token. Embedded quotes are not supported (this is a demo shell).
     */
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
