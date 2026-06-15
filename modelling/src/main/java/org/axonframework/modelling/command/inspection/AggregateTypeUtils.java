package org.axonframework.modelling.command.inspection;

import org.axonframework.modelling.command.AggregateRoot;

import static org.axonframework.common.annotation.AnnotationUtils.findAnnotationAttributes;

/**
 * Utility class providing static methods to retrieve the type as a {@link String} for a given aggregate {@link Class}.
 *
 * @author Mateusz Nowak
 * @since 4.13.2
 */
public class AggregateTypeUtils {

    /**
     * Resolves the declared type of the given aggregate {@code type}. This is the {@link AggregateRoot#type()} when it
     * is present and non-empty, and otherwise the {@link Class#getSimpleName() simple name} of the {@code type}.
     * <p>
     * The declared type is the value used to identify an aggregate's snapshots and events (it is stored as the
     * {@link org.axonframework.eventhandling.DomainEventData#getType() type} of a snapshot). As such, it is the value a
     * {@code SnapshotFilter} should match against.
     *
     * @param type the aggregate class to resolve the declared type for
     * @return the declared type of the given aggregate {@code type}
     * @since 4.13.2
     */
    public static String declaredTypeOf(Class<?> type) {
        return findAnnotationAttributes(type, AggregateRoot.class)
                .map(attributes -> (String) attributes.get("type"))
                .filter(declaredType -> !declaredType.isEmpty())
                .orElse(type.getSimpleName());
    }

    private AggregateTypeUtils() {
        // Utility class
    }
}
