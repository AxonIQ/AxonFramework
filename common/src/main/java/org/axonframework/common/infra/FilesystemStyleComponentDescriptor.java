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

package org.axonframework.common.infra;

import org.axonframework.common.configuration.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link ComponentDescriptor} implementation inspired by filesystem structures. Components are represented as
 * "directories" with properties as "files" and references as "symbolic links". This creates a clear hierarchical
 * representation with explicit paths to show component relationships.
 * <p>
 * Each component is assigned a path in the virtual filesystem. References to components that have already been
 * described are represented as symbolic links, indicated with the {@code -> /path/to/component} syntax, preventing
 * infinite recursion in circular references.
 * <p>
 * Example output:
 * <pre>
 * /
 * ├── systemComponent/
 * │   ├── _ref: 123512
 * │   ├── _type: EventProcessor
 * │   ├── name: my-processor
 * │   └── configuration/
 * │       ├── _ref: 123513
 * │       ├── _type: ProcessorConfiguration
 * │       ├── batchSize: 100
 * │       └── processor -> /systemComponent
 * </pre>
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public class FilesystemStyleComponentDescriptor implements ComponentDescriptor {

    private static final String ROOT_PATH = "/";
    private static final String PATH_SEPARATOR = "/";

    private final Map<Object, String> componentPaths;
    private final Map<String, @Nullable Object> processedComponents;
    private final String currentPath;

    /**
     * Constructs a new {@code FilesystemComponentDescriptor} as the root of the hierarchy.
     */
    public FilesystemStyleComponentDescriptor() {
        this(new IdentityHashMap<>(), ROOT_PATH);
    }

    /**
     * Private constructor used for creating nested descriptors that share the component paths map.
     *
     * @param componentPaths Map containing paths to already processed components.
     * @param currentPath    The current path in the virtual filesystem.
     */
    private FilesystemStyleComponentDescriptor(Map<Object, String> componentPaths, String currentPath) {
        this.componentPaths = componentPaths;
        this.currentPath = currentPath;
        this.processedComponents = new LinkedHashMap<>();
    }

    @Override
    public void describeProperty(String name, @Nullable Object object) {
        if (object instanceof DescribableComponent component) {
            describeComponent(name, component);
        } else {
            processedComponents.put(name, object);
        }
    }

    private void describeComponent(String name, DescribableComponent component) {
        boolean componentSeenAlready = componentPaths.containsKey(component);
        if (componentSeenAlready) {
            var existingPath = componentPaths.get(component);
            processedComponents.put(name, new SymbolicLink(existingPath));
            return;
        }

        var componentPath = currentPathChild(name);
        componentPaths.put(component, componentPath);
        processedComponents.put(name, componentDescriptor(component, componentPath));
    }

    private String currentPathChild(String name) {
        return currentPath.equals(ROOT_PATH)
                ? currentPath + name
                : currentPath + PATH_SEPARATOR + name;
    }

    @Override
    public void describeProperty(String name, @Nullable Collection<?> collection) {
        if (collection == null) {
            processedComponents.put(name, null);
            return;
        }
        var items = new ArrayList<>();

        int index = 0;
        for (var item : collection) {
            var property = item instanceof DescribableComponent component
                    ? describeComponentInCollection(name, index, component)
                    : item;
            items.add(property);
            index++;
        }

        processedComponents.put(name, items);
    }

    private Object describeComponentInCollection(
            String name,
            int index,
            DescribableComponent component
    ) {
        boolean componentSeenAlready = componentPaths.containsKey(component);
        if (componentSeenAlready) {
            var existingPath = componentPaths.get(component);
            return new SymbolicLink(existingPath);
        } else {
            var itemName = name + "[" + index + "]";
            var itemPath = currentPathChild(itemName);
            componentPaths.put(component, itemPath);
            return componentDescriptor(component, itemPath);
        }
    }

    @Override
    public void describeProperty(String name, @Nullable Map<?, ?> map) {
        if (map == null) {
            processedComponents.put(name, null);
            return;
        }
        var mappedItems = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            var key = entry.getKey().toString();
            var value = entry.getValue();
            var property = value instanceof DescribableComponent component
                    ? describeComponentInMap(name, key, component)
                    : value;
            mappedItems.put(key, property);
        }
        processedComponents.put(name, mappedItems);
    }

    private Object describeComponentInMap(
            String name,
            String key,
            DescribableComponent component
    ) {
        boolean componentSeenAlready = componentPaths.containsKey(component);
        if (componentSeenAlready) {
            var existingPath = componentPaths.get(component);
            return new SymbolicLink(existingPath);
        } else {
            var itemName = name + "[" + key + "]";
            var itemPath = currentPathChild(itemName);
            componentPaths.put(component, itemPath);
            return componentDescriptor(component, itemPath);
        }
    }

    private ComponentDescriptor componentDescriptor(
            DescribableComponent component,
            String itemPath
    ) {
        var descriptor = new FilesystemStyleComponentDescriptor(componentPaths, itemPath);
        var type = component instanceof Component<?>
                ? ((Component<?>) component).identifier().typeAsClass().getName()
                : component.getClass().getName();
        descriptor.describeProperty("_ref", System.identityHashCode(component));
        descriptor.describeProperty("_type", type);
        component.describeTo(descriptor);
        return descriptor;
    }

    @Override
    public void describeProperty(String name, @Nullable String value) {
        processedComponents.put(name, value);
    }

    @Override
    public void describeProperty(String name, @Nullable Long value) {
        processedComponents.put(name, value);
    }

    @Override
    public void describeProperty(String name, @Nullable Boolean value) {
        processedComponents.put(name, value);
    }

    /**
     * Provides a description of what has been described in this Descriptor as a String.
     *
     * @return a description of what has been described in this Descriptor
     */
    public String describe() {
        try {
            return new TreeRenderer().render(processedComponents);
        } catch (Exception e) {
            throw new ComponentDescriptorException(
                    "Error generating Filesystem style description",
                    e
            );
        }
    }

    private record SymbolicLink(String targetPath) {

        private static final String SYMLINK_INDICATOR = " -> ";

        @Override
        public String toString() {
            return SYMLINK_INDICATOR + targetPath;
        }
    }

    private static class TreeRenderer {

        private static final String CORNER = "└── ";
        private static final String TEE = "├── ";
        private static final String VERTICAL = "│   ";
        private static final String SPACE = "    ";

        private final StringBuilder result = new StringBuilder();

        String render(Map<String, @Nullable Object> properties) {
            result.append(ROOT_PATH).append("\n");
            var context = new RenderContext("", "");
            render(properties, context);
            return result.toString();
        }

        private void render(Map<String, @Nullable Object> properties, RenderContext context) {
            var entries = new ArrayList<>(properties.entrySet());

            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                var name = entry.getKey();
                var value = entry.getValue();

                var isLast = (i == entries.size() - 1);
                renderProperty(name, value, context, isLast);
            }
        }

        private void renderProperty(
                String name,
                @Nullable Object value,
                RenderContext context,
                boolean isLastInCollection
        ) {
            switch (value) {
                case FilesystemStyleComponentDescriptor descriptor -> renderComponentDirectory(name,
                                                                                               descriptor,
                                                                                               context,
                                                                                               isLastInCollection);
                case List<?> list -> renderList(name, list, context, isLastInCollection);
                case Map<?, ?> map -> renderMap(name, map, context, isLastInCollection);
                case SymbolicLink link -> renderSymlink(name, link, context, isLastInCollection);
                case null, default -> renderSimpleValue(name, value, context, isLastInCollection);
            }
        }

        private void renderComponentDirectory(
                String name,
                FilesystemStyleComponentDescriptor descriptor,
                RenderContext context,
                boolean isLastInCollection
        ) {
            result.append(context.indent).append(connectorForProperty(isLastInCollection)).append(name).append("/\n");
            var childContext = context.indented(name, isLastInCollection);
            render(descriptor.processedComponents, childContext);
        }

        private void renderList(
                String name,
                List<?> list,
                RenderContext context,
                boolean isLastInCollection
        ) {
            // Render the list name as a directory
            result.append(context.indent).append(connectorForProperty(isLastInCollection)).append(name).append("/\n");

            var listContext = context.indented(name, isLastInCollection);
            for (int j = 0; j < list.size(); j++) {
                var item = list.get(j);
                var isLastItem = (j == list.size() - 1);
                var key = "[" + j + "]";
                renderMapOrListEntry(key, item, listContext, isLastItem);
            }
        }

        private String connectorForProperty(boolean isLastInCollection) {
            return isLastInCollection ? CORNER : TEE;
        }

        private void renderMapOrListEntry(
                String key,
                Object item,
                RenderContext listContext,
                boolean isLastInCollection
        ) {
            result.append(listContext.indent).append(connectorForProperty(isLastInCollection)).append(key);

            if (item instanceof FilesystemStyleComponentDescriptor itemDescriptor) {
                result.append("/\n");
                var itemContext = listContext.indented(key, isLastInCollection);
                render(itemDescriptor.processedComponents, itemContext);
            } else if (item instanceof SymbolicLink link) {
                result.append(link).append("\n");
            } else {
                result.append(": ").append(valueOrNull(item)).append("\n");
            }
        }

        private void renderMap(
                String name,
                Map<?, ?> map,
                RenderContext context,
                boolean isLastInCollection
        ) {
            // Render the map name as a directory
            result.append(context.indent).append(connectorForProperty(isLastInCollection)).append(name).append("/\n");

            // Create a new context for map entries
            var mapContext = context.indented(name, isLastInCollection);

            // Render each entry in the map
            var mapEntries = new ArrayList<>(map.entrySet());
            mapEntries.sort(Comparator.comparing(entry -> entry.getKey().toString()));
            for (int j = 0; j < mapEntries.size(); j++) {
                var mapEntry = mapEntries.get(j);
                var key = mapEntry.getKey().toString();
                var mapValue = mapEntry.getValue();
                var isLastMapEntry = (j == mapEntries.size() - 1);
                renderMapOrListEntry(key, mapValue, mapContext, isLastMapEntry);
            }
        }

        private void renderSymlink(String name, SymbolicLink link, RenderContext context, boolean isLastInCollection) {
            result.append(context.indent).append(connectorForProperty(isLastInCollection)).append(name)
                  .append(link).append("\n");
        }

        private void renderSimpleValue(String name, @Nullable Object value, RenderContext context, boolean isLastInCollection) {
            result.append(context.indent).append(connectorForProperty(isLastInCollection)).append(name)
                  .append(": ").append(valueOrNull(value)).append("\n");
        }

        private String valueOrNull(@Nullable Object value) {
            return value == null ? "null" : value.toString();
        }

        private record RenderContext(String path, String indent) {

            RenderContext indented(String name, boolean isLast) {
                var childPath = path.isEmpty() ? name : path + PATH_SEPARATOR + name;
                var childIndent = indent + (isLast ? SPACE : VERTICAL);
                return new RenderContext(childPath, childIndent);
            }
        }
    }
}