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
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.EnrolmentReadModel;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.GetCourseCatalogView;
import org.axonframework.examples.demo.coursecatalog.shared.region.RequestRegion;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.WelcomeMessageView;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.catalog.write.enrollstudent.EnrollStudent;
import org.axonframework.examples.demo.coursecatalog.catalog.write.publishcourse.PublishCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.write.updatecoursecapacity.UpdateCourseCapacity;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Interactive stdin REPL with arrow-key history and line editing. Started by
 * {@link CourseCatalogApplication} when the {@code --keep-alive} program argument
 * is set. Exits on {@code exit}, {@code quit}, Ctrl+D, or Ctrl+C, after which
 * {@code CourseCatalogApplication#main} runs its normal {@code finally} block and
 * shuts the configuration down.
 */
final class InteractiveShell {

    private static final Logger logger = LoggerFactory.getLogger(InteractiveShell.class);
    private static final String PROMPT = "course-catalog> ";

    private final LineReader reader;
    private final PrintWriter out;
    private final CommandGateway commands;
    private final QueryGateway queries;

    /**
     * Opens a terminal, builds a shell on it, and runs the REPL until the user exits.
     *
     * @param configuration the running Axon configuration to dispatch against
     */
    static void run(AxonConfiguration configuration) {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            new InteractiveShell(terminal, configuration).loop();
        } catch (IOException e) {
            logger.warn("Could not open interactive terminal, exiting shell: {}", e.getMessage());
        }
    }

    private InteractiveShell(Terminal terminal, AxonConfiguration configuration) {
        this.reader = LineReaderBuilder.builder().terminal(terminal).build();
        this.out = terminal.writer();
        this.commands = configuration.getComponent(CommandGateway.class);
        this.queries = configuration.getComponent(QueryGateway.class);
    }

    private void loop() {
        printBanner();
        while (true) {
            String line = readLine();
            if (line == null) {
                return;
            }
            if (line.isEmpty()) {
                continue;
            }
            if (!dispatchSafely(line)) {
                return;
            }
        }
    }

    /** @return the trimmed input line, or {@code null} when the user signals exit (Ctrl+C / Ctrl+D) */
    private @Nullable String readLine() {
        try {
            String raw = reader.readLine(PROMPT);
            return raw == null ? null : raw.trim();
        } catch (UserInterruptException | EndOfFileException e) {
            return null;
        }
    }

    /**
     * Dispatches one command and turns any thrown {@link RuntimeException} into a
     * printed {@code [error]} line so the REPL stays alive.
     *
     * @param line the trimmed command line
     * @return false to exit the shell, true to keep going
     */
    private boolean dispatchSafely(String line) {
        try {
            return dispatch(line);
        } catch (RuntimeException e) {
            print("[error] " + e.getMessage());
            return true;
        }
    }

    /** @return false to exit the shell, true to keep going */
    private boolean dispatch(String line) {
        List<String> tokens = tokenize(line);
        String head = tokens.getFirst();
        return switch (head) {
            case "help", "?" -> { printHelp(); yield true; }
            case "view" -> { printView(); yield true; }
            case "publish" -> { publish(tokens); yield true; }
            case "capacity" -> { capacity(tokens); yield true; }
            case "enroll" -> { enroll(tokens); yield true; }
            case "welcome" -> { welcome(tokens); yield true; }
            case "exit", "quit" -> false;
            default -> { unknown(head); yield true; }
        };
    }

    // ------------------------------------------------------------------------
    // Command handlers
    // ------------------------------------------------------------------------

    private void publish(List<String> tokens) {
        requireArgs(tokens, 5, "publish <courseId> \"<name>\" <min> <max>");
        commands.sendAndWait(new PublishCourse(
                CourseId.of(tokens.get(1)),
                tokens.get(2),
                new CapacityRange(parseInt(tokens.get(3)), parseInt(tokens.get(4)))));
        print("[ok] published " + tokens.get(1));
    }

    private void capacity(List<String> tokens) {
        requireArgs(tokens, 4, "capacity <courseId> <min> <max>");
        commands.sendAndWait(new UpdateCourseCapacity(
                CourseId.of(tokens.get(1)),
                new CapacityRange(parseInt(tokens.get(2)), parseInt(tokens.get(3)))));
        print("[ok] capacity updated for " + tokens.get(1));
    }

    private void enroll(List<String> tokens) {
        requireArgs(tokens, 3, 4, "enroll <courseId> <studentId> [region]");
        EnrollStudent command = new EnrollStudent(CourseId.of(tokens.get(1)), StudentId.of(tokens.get(2)));
        if (tokens.size() == 4) {
            // Carry the region as command metadata. The RequestRegionCommandInterceptor lifts it onto
            // the ProcessingContext, which then threads into the transformation chain when the student
            // entity is sourced, letting StudentRegisteredV2ToV3 backfill the region of a historic student.
            String region = tokens.get(3);
            commands.send(command, Metadata.with(RequestRegion.METADATA_KEY, region)).wait(Object.class);
            print("[ok] enrolled " + tokens.get(2) + " in " + tokens.get(1) + " (region " + region + ")");
        } else {
            commands.sendAndWait(command);
            print("[ok] enrolled " + tokens.get(2) + " in " + tokens.get(1));
        }
    }

    private void welcome(List<String> tokens) {
        requireArgs(tokens, 2, "welcome <studentId>");
        StudentId studentId = StudentId.of(tokens.get(1));
        // The body shown here was lifted from a historic 0.x WelcomeMessageSent by the
        // transformation chain on the read path; the legacy 'subject' field is gone.
        queryCatalog().welcomeMessages().stream()
                      .filter(message -> message.studentId().equals(studentId))
                      .findFirst()
                      .ifPresentOrElse(
                              message -> print("  " + message.studentId() + ": " + message.body()),
                              () -> print("[error] no welcome message for " + tokens.get(1)));
    }

    private void unknown(String head) {
        print("[error] unknown command '" + head + "'. Type 'help' for the list.");
    }

    // ------------------------------------------------------------------------
    // Output helpers
    // ------------------------------------------------------------------------

    private void printBanner() {
        print("");
        print("Interactive shell ready. Type 'help' for available commands, 'exit' to shut down.");
        print("Use the up and down arrows to navigate previous commands.");
    }

    private void printHelp() {
        print("Commands:");
        print("  publish  <courseId> \"<name>\" <min> <max>   Publish a new course");
        print("  capacity <courseId> <min> <max>             Update a course's capacity range");
        print("  enroll   <courseId> <studentId> [region]    Enroll a student; the optional region rides");
        print("                                              along as metadata and is backfilled onto");
        print("                                              historic students via the processing context");
        print("  view                                        Print the catalog view");
        print("  welcome  <studentId>                        Show a student's welcome message");
        print("  help                                        Show this message");
        print("  exit                                        Shut down");
    }

    private void printView() {
        CourseCatalogView view = queryCatalog();
        print("Registered students: " + view.registeredStudents());
        print("Courses (" + view.courses().size() + "):");
        for (CatalogViewReadModel course : view.courses()) {
            print("  - " + course.courseId()
                          + " \"" + course.name() + "\""
                          + " range=" + course.range()
                          + " enrolments=" + course.enrolments()
                          + (course.registrationClosed() ? " [closed]" : ""));
        }
        print("Enrolments (" + view.enrolments().size() + "):");
        for (EnrolmentReadModel enrolment : view.enrolments()) {
            print("  - " + enrolment.studentId()
                          + " in " + enrolment.courseId()
                          + " region=" + enrolment.region());
        }
        print("Announcements (" + view.announcements().size() + "):");
        for (String announcement : view.announcements()) {
            print("  - " + announcement);
        }
        print("Welcome messages (" + view.welcomeMessages().size() + "):");
        for (WelcomeMessageView message : view.welcomeMessages()) {
            print("  - " + message.studentId() + ": " + message.body());
        }
        print("System heartbeats seen by the projection: " + view.heartbeatsSeen()
                      + " (stored but dropped on read, so none are processed)");
    }

    private CourseCatalogView queryCatalog() {
        return queries.query(new GetCourseCatalogView(), CourseCatalogView.class, null)
                      .orTimeout(5, TimeUnit.SECONDS)
                      .join();
    }

    private void print(String line) {
        out.println(line);
        out.flush();
    }

    // ------------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------------

    private static void requireArgs(List<String> tokens, int expected, String usage) {
        requireArgs(tokens, expected, expected, usage);
    }

    private static void requireArgs(List<String> tokens, int min, int max, String usage) {
        if (tokens.size() < min || tokens.size() > max) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private static int parseInt(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + token + "' is not an integer", e);
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
