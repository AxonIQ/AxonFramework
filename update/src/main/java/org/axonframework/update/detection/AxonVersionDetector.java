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

package org.axonframework.update.detection;

import org.axonframework.common.annotation.Internal;
import org.axonframework.update.api.Artifact;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

/**
 * Utility class to detect Axon Framework versions from the classpath. It scans for Maven's {@code pom.properties} files
 * in the classpath and extracts the Axon Framework module versions. If an error occurs during detection, it logs the
 * error and returns an empty list.
 * <p>
 * Will only detect Axon Framework modules that are part of the Axon Framework or Axoniq ecosystem, as defined by the
 * group IDs:
 * <ul>
 *     <li>org.axonframework</li>
 *     <li>io.axoniq</li>
 * </ul>
 * <p>
 * This class is not intended to be instantiated, and all methods are static.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
@Internal
public final class AxonVersionDetector {

    private static final Logger logger = LoggerFactory.getLogger(AxonVersionDetector.class);
    private static final List<String> GROUP_IDS = List.of(
            "org/axonframework",
            "io/axoniq"
    );

    private AxonVersionDetector() {
        // Prevent instantiation
    }

    /**
     * Detects Axon Framework modules in the classpath and returns their versions. If an error occurs during detection,
     * it logs the error and returns an empty list.
     *
     * @return A list of detected Axon Framework modules with their versions.
     */
    public static List<@Nullable Artifact> safeDetectAxonModules() {
        try {
            return detectAxonModules()
                    .stream()
                    .distinct()
                    .toList();
        } catch (Exception e) {
            logger.error("Failed to detect Axon Framework modules", e);
            return List.of();
        }
    }

    private static List<@Nullable Artifact> detectAxonModules() {
        List<@Nullable Artifact> foundVersions = new LinkedList<>();
        List<URL> urlsToCheck = new LinkedList<>();
        for (String groupId : GROUP_IDS) {
            try {
                Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                                                   .getResources("META-INF/maven/");
                while (resources.hasMoreElements()) {
                    urlsToCheck.add(resources.nextElement());
                }
            } catch (IOException e) {
                logger.debug("Failed to retrieve Axon Framework modules for group ID: {}", groupId, e);
            }
        }
        for (URL url : urlsToCheck) {
            try {
                String filePath = url.getFile();
                if (GROUP_IDS.stream().noneMatch(filePath::contains) && !filePath.contains("test-classes")) {
                    continue; // Skip URLs that do not match the Axoniq group IDs
                }
                if (url.getProtocol().equals("jar")) {
                    foundVersions.addAll(extractVersionFromJar(url));
                } else if (url.getProtocol().equals("file")) {
                    foundVersions.addAll(extractVersionFromDirectory(url));
                }
            } catch (Exception e) {
                logger.debug("Failed to read Axon Framework module at URL: {}", url, e);
            }
        }
        return foundVersions;
    }

    private static List<@Nullable Artifact> extractVersionFromJar(URL url) throws IOException {
        // The URL format for JAR files is typically "file:/path/to/jarfile.jar!/META-INF/maven/...".
        // We need to extract the path to the JAR file, so we remove the "file:" prefix and everything after the "!" character.
        String jarFilePath = url.getPath().substring(5, url.getPath().indexOf("!"));
        try (JarFile jarFile = new JarFile(new File(jarFilePath))) {
            return jarFile.stream()
                          .filter(entry -> entry.getName().startsWith("META-INF/maven/"))
                          .filter(entry -> entry.getName().endsWith("/pom.properties"))
                          .map(entry -> {
                              try (InputStream inputStream = jarFile.getInputStream(entry)) {
                                  return mapToAxonVersion(inputStream);
                              } catch (IOException e) {
                                  logger.debug("Failed to read pom.properties from JAR entry: {}", entry.getName(), e);
                                  return null;
                              }
                          })
                          .toList();
        }
    }

    private static Artifact mapToAxonVersion(InputStream inputStream) throws IOException {
        Properties mavenProps = new Properties();
        mavenProps.load(inputStream);
        return new Artifact(
                mavenProps.getProperty("groupId"),
                mavenProps.getProperty("artifactId"),
                mavenProps.getProperty("version")
        );
    }

    private static List<Artifact> extractVersionFromDirectory(URL url) throws URISyntaxException {
        File file = new File(url.toURI());
        List<Artifact> foundVersions = new LinkedList<>();
        if (file.isDirectory()) {
            List<File> filesToCheck = scanDirectoryForPomProperties(file);
            for (File pomFile : filesToCheck) {
                try {
                    Artifact version = mapToAxonVersion(Files.newInputStream(pomFile.toPath()));
                    foundVersions.add(version);
                } catch (Exception e) {
                    logger.debug("Unexpected error while processing pom.properties file: {}",
                                 pomFile.getAbsolutePath(), e);
                }
            }
        }
        return foundVersions;
    }

    private static List<File> scanDirectoryForPomProperties(File dir) {
        List<File> files = new LinkedList<>();
        scanRecursivelyForPomProperties(dir, files);
        return files;
    }

    private static void scanRecursivelyForPomProperties(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                scanRecursivelyForPomProperties(child, files);
            } else if (child.getName().equals("pom.properties")) {
                files.add(child);
            }
        }
    }
}
