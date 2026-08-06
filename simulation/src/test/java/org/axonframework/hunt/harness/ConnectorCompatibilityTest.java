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

package org.axonframework.hunt.harness;

import io.axoniq.framework.axonserver.connector.event.AxonServerEventStorageEngine;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answers, in about a second and before any container starts, whether a released Axon Server connector can be loaded
 * against this reactor at all.
 * <p>
 * <b>This exists because the thing that blocked the Axon Server arm was not a test problem.</b> It was an undetected
 * binary-compatibility break between the framework and its own released client library: an abstract method added to a
 * storage-engine interface that the released connector had not implemented. {@code javac} resolves a call against the
 * interface and accepts it; the JVM refuses it at the first invocation with {@code AbstractMethodError}. So the failure
 * arrives ten minutes into a container run, from a stack trace that names a method rather than a version, and it looks
 * like a broken harness. This check turns that into a named list of methods, before anything is started.
 * <p>
 * <b>What it computes.</b> For every concrete class in a connector jar, every abstract method anywhere in that class's
 * hierarchy which the class neither declares nor inherits a {@code default} for. The framework side of the comparison is
 * this reactor's classes, taken from the test classpath; only the connector's own packages come from the jar under
 * examination, which is what makes the answer "this connector against this framework" rather than against the framework
 * the connector shipped with.
 * <p>
 * <b>What it asserts.</b> That the set is exactly what {@code formal/CONNECTOR-COMPATIBILITY.md} records for the shipped
 * combination: covered either by a harness shim, which must be named, or by a method no scenario drives, which must also
 * be named. Anything else fails the build. A new abstract method on a storage-engine interface therefore breaks this
 * check rather than the Axon Server arm, and breaks it in seconds.
 * <p>
 * Point it at another artefact to find out what that combination would need:
 * <pre>{@code
 * ./mvnw -q -Phunt -pl simulation -o test -Dtest=ConnectorCompatibilityTest \
 *     -Dhunt.connectorJar=$HOME/.m2/repository/io/axoniq/framework/axon-server-connector/5.1.2/axon-server-connector-5.1.2.jar
 * }</pre>
 * The report is printed either way, so a run against another version tells you what to write in the table even when the
 * assertion for the shipped version is the one that fails.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class ConnectorCompatibilityTest {

    /**
     * The property naming a connector jar to examine instead of the one on the test classpath.
     */
    private static final String JAR_PROPERTY = "hunt.connectorJar";

    /**
     * The connector's own package prefix, which is the only part of the comparison taken from the jar rather than from
     * this reactor.
     */
    private static final String CONNECTOR_PACKAGE = "io.axoniq.framework.axonserver.";

    /**
     * The methods the harness supplies, each of which must appear in the report or the shim has become dead code.
     */
    private static final Set<String> SHIMMED = Set.of(
            "AxonServerEventStorageEngine -> EventStorageEngine.source(SourcingCondition, ProcessingContext)");

    /**
     * The methods left unshimmed on purpose, because no scenario in this suite drives them.
     * <p>
     * Shimming a method nothing exercises would model something nothing measures, and would hide the fact that the
     * combination is incomplete. Naming them here is what keeps that honest: the day a scenario loads a snapshot on this
     * backend, it fails on a method this list says is not there.
     * <p>
     * <b>"Unimplemented" here means the overload, not the class.</b> {@code AxonServerSnapshotStore} is a fully
     * implemented snapshot store with working public bodies for the pre-context signatures; what it lacks is the
     * context-carrying overloads, which is all this check looks at. See
     * {@code formal/CONNECTOR-COMPATIBILITY.md} section 4.
     */
    private static final Set<String> NOT_DRIVEN = Set.of(
            "AggregateBasedAxonServerEventStorageEngine -> EventStorageEngine.source(SourcingCondition, "
                    + "ProcessingContext)",
            "AxonServerSnapshotStore -> SnapshotStore.load(QualifiedName, Object, ProcessingContext)",
            "AxonServerSnapshotStore -> SnapshotStore.store(QualifiedName, Object, Snapshot, ProcessingContext)",
            "AxonServerSnapshotStore -> DescribableComponent.describeTo(ComponentDescriptor)");

    /**
     * Returns the jar to examine: the one named by {@value #JAR_PROPERTY}, or the one the classpath's connector was
     * loaded from.
     */
    private static Path connectorJar() {
        String requested = System.getProperty(JAR_PROPERTY);
        if (requested != null && !requested.isBlank() && !requested.startsWith("$")) {
            return Path.of(requested);
        }
        try {
            return Path.of(AxonServerEventStorageEngine.class.getProtectionDomain()
                                                             .getCodeSource()
                                                             .getLocation()
                                                             .toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("Unable to locate the connector on the test classpath.", e);
        }
    }

    /**
     * Returns every abstract method the classes in the given jar leave unimplemented against this reactor.
     * <p>
     * A class loader that prefers the jar for the connector's own packages and this reactor for everything else, so the
     * comparison is the one that matters. A class the jar references and this reactor does not have is reported as an
     * unresolvable rather than swallowed, because a missing type is a different incompatibility from a missing method and
     * conflating them would hide it.
     */
    private static Report scan(Path jar) {
        List<String> unimplemented = new ArrayList<>();
        List<String> unresolvable = new ArrayList<>();
        int classes = 0;
        try (JarFile file = new JarFile(jar.toFile());
             URLClassLoader loader = new ArtifactFirstLoader(new URL[]{url(jar)},
                                                            ConnectorCompatibilityTest.class.getClassLoader())) {
            for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); ) {
                String entry = entries.nextElement().getName();
                if (!entry.endsWith(".class") || entry.contains("module-info")) {
                    continue;
                }
                classes++;
                String name = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
                Class<?> type;
                try {
                    type = Class.forName(name, false, loader);
                } catch (Throwable t) {
                    unresolvable.add(name + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    continue;
                }
                if (type.isInterface() || type.isAnnotation() || Modifier.isAbstract(type.getModifiers())) {
                    continue;
                }
                for (Method method : abstractMethodsOf(type)) {
                    if (!implemented(type, method)) {
                        unimplemented.add(describe(type, method));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read the connector jar [" + jar + "].", e);
        }
        return new Report(jar, classes, new TreeSet<>(unimplemented), new TreeSet<>(unresolvable));
    }

    private static URL url(Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Unable to address the connector jar [" + jar + "].", e);
        }
    }

    private static List<Method> abstractMethodsOf(Class<?> type) {
        List<Method> abstractMethods = new ArrayList<>();
        Deque<Class<?>> todo = new ArrayDeque<>(List.of(type));
        Set<Class<?>> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            Class<?> next = todo.poll();
            if (next == null || !seen.add(next)) {
                continue;
            }
            Arrays.stream(next.getDeclaredMethods())
                  .filter(method -> Modifier.isAbstract(method.getModifiers()))
                  .forEach(abstractMethods::add);
            if (next.getSuperclass() != null) {
                todo.add(next.getSuperclass());
            }
            todo.addAll(List.of(next.getInterfaces()));
        }
        return abstractMethods;
    }

    private static boolean implemented(Class<?> type, Method abstractMethod) {
        for (Class<?> walk = type; walk != null; walk = walk.getSuperclass()) {
            try {
                if (!Modifier.isAbstract(walk.getDeclaredMethod(abstractMethod.getName(),
                                                                abstractMethod.getParameterTypes())
                                             .getModifiers())) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
                // Keep walking; a superclass or a default method may still carry it.
            }
        }
        Deque<Class<?>> todo = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        for (Class<?> walk = type; walk != null; walk = walk.getSuperclass()) {
            todo.addAll(List.of(walk.getInterfaces()));
        }
        while (!todo.isEmpty()) {
            Class<?> next = todo.poll();
            if (next == null || !seen.add(next)) {
                continue;
            }
            try {
                if (next.getDeclaredMethod(abstractMethod.getName(), abstractMethod.getParameterTypes()).isDefault()) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
                // Keep walking the interface hierarchy.
            }
            todo.addAll(List.of(next.getInterfaces()));
        }
        return false;
    }

    private static String describe(Class<?> type, Method method) {
        return type.getSimpleName() + " -> " + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                + Arrays.stream(method.getParameterTypes())
                        .map(Class::getSimpleName)
                        .reduce((first, second) -> first + ", " + second)
                        .map(parameters -> "(" + parameters + ")")
                        .orElse("()");
    }

    /**
     * What one scan found.
     *
     * @param jar           the artefact examined
     * @param classes       how many class entries it holds
     * @param unimplemented the abstract methods left unimplemented against this reactor
     * @param unresolvable  the classes that could not be loaded against this reactor at all
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    private record Report(Path jar, int classes, Set<String> unimplemented, Set<String> unresolvable) {

        void print() {
            System.out.println("CONNECTOR COMPATIBILITY " + jar.getFileName()
                                       + " against framework " + HuntBackend.frameworkVersion());
            System.out.println("  classes=" + classes + " unresolvable=" + unresolvable.size()
                                       + " unimplemented=" + unimplemented.size());
            unimplemented.forEach(method -> System.out.println("  UNIMPLEMENTED " + method));
            unresolvable.forEach(type -> System.out.println("  UNRESOLVABLE " + type));
            System.out.println("  verdict=" + (unresolvable.isEmpty() && unimplemented.isEmpty()
                    ? "supported" : unresolvable.isEmpty() ? "shimmable" : "incompatible"));
        }
    }

    @Nested
    class TheConnectorTheAxonServerArmLinksAgainst {

        @Test
        void leavesNothingUnimplementedThatIsNotEitherShimmedOrUndriven() {
            // given the connector artefact the Axon Server arm runs against
            Path jar = connectorJar();
            assertThat(jar).as("the connector artefact to examine").exists();

            // when it is compared, method by method, against this reactor's own interfaces
            Report report = scan(jar);
            report.print();

            // then every class of it resolves. A type this reactor no longer has is a different incompatibility from a
            // method it has newly declared, and reporting the two the same way would hide a package move behind a
            // missing method.
            assertThat(report.unresolvable())
                    .as("classes of %s that cannot be loaded against this framework", jar.getFileName())
                    .isEmpty();

            // and every method it leaves unimplemented is one the harness supplies or one no scenario drives. This is
            // the check that turns an AbstractMethodError ten minutes into a container run into a named list before
            // anything starts: an abstract method added to a storage-engine interface breaks it here, in a second.
            assertThat(report.unimplemented())
                    .as("unimplemented methods of %s, which must be shimmed or declared undriven in "
                                + "formal/CONNECTOR-COMPATIBILITY.md", jar.getFileName())
                    .allSatisfy(method -> assertThat(SHIMMED.contains(method) || NOT_DRIVEN.contains(method))
                            .as("%s is neither shimmed nor recorded as undriven", method)
                            .isTrue());

            // and the shim is not dead code. A shimmed method the connector has meanwhile implemented itself should be
            // deleted rather than left overriding a working implementation with the harness's approximation of it.
            assertThat(report.unimplemented())
                    .as("the shimmed method must still be one the connector lacks")
                    .containsAll(SHIMMED);
        }
    }

    @Nested
    class TheCompatibilityTable {

        @Test
        void recordsEveryVersionTheGateHasBeenRunAgainstAndNamesTheShimmedMethods() {
            // given the checked-in table a future reader consults instead of rediscovering this
            Path table = Path.of("..", "formal", "CONNECTOR-COMPATIBILITY.md");

            // when it is read
            assertThat(table).as("the compatibility table").exists();
            String contents = readAll(table);

            // then it records the combination this build actually ran, so a reader can tell the table apart from an
            // aspiration. A version column nobody ever ran the gate against is worse than an absent one.
            assertThat(contents)
                    .as("the table must name the connector the arm ships on and the method it shims")
                    .contains(AxonServerHuntBackend.CONNECTOR_VERSION)
                    .contains("source(SourcingCondition, ProcessingContext)");
        }

        private static String readAll(Path path) {
            try {
                return Files.readString(path);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read [" + path + "].", e);
            }
        }
    }

    /**
     * Loads the connector's own packages from the artefact under examination and everything else from this reactor.
     * <p>
     * Parent-first loading would find the connector already on the test classpath and quietly examine that one instead,
     * which makes pointing the gate at another version silently do nothing.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    private static final class ArtifactFirstLoader extends URLClassLoader {

        private ArtifactFirstLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(CONNECTOR_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException absent) {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
