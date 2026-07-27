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

package org.axonframework.hunt.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renders the reference model's vocabulary into the plain maps a history record carries, and reads it back.
 * <p>
 * This is the contract between whatever drives the store and whatever checks it: a recorder encodes an append's
 * condition and batch with this class, and the model-conformance checker decodes them with the same one. Both sides
 * changing together is the point. The rendering uses JDK types only, so it stays independent of the serializer the
 * recorder happens to use.
 * <p>
 * The rendered shapes are:
 * <pre>{@code
 * tag        {"key": "student", "value": "s-1"}
 * criterion  {"tags": [tag, ...], "types": ["StudentEnrolled", ...]}
 * condition  {"marker": 7, "criteria": [criterion, ...]}
 * event      {"id": "e-1", "type": "StudentEnrolled", "tags": [tag, ...]}
 * }</pre>
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class DcbHistoryCodec {

    /**
     * The value key an append's condition marker is rendered under.
     */
    public static final String MARKER = "marker";

    /**
     * The value key an append's boundary is rendered under.
     */
    public static final String CRITERIA = "criteria";

    /**
     * The value key an append's batch is rendered under.
     */
    public static final String EVENTS = "events";

    /**
     * The value key a list of event identifiers is rendered under, used by commit, rollback, delivery and scan
     * records.
     */
    public static final String EVENT_IDS = "eventIds";

    /**
     * The value key a single event identifier is rendered under, used by delivery records.
     */
    public static final String EVENT_ID = "eventId";

    private static final String TAGS = "tags";
    private static final String TYPES = "types";
    private static final String KEY = "key";
    private static final String VALUE = "value";
    private static final String ID = "id";
    private static final String TYPE = "type";

    private DcbHistoryCodec() {
        // Utility class.
    }

    /**
     * Renders an append's condition and batch as a history record's value.
     *
     * @param condition the condition the append was attempted under
     * @param batch     the events offered, in offer order
     * @return the value map to record on the invocation
     */
    public static Map<String, Object> encodeAppend(ModelAppendCondition condition, List<ModelEvent> batch) {
        Objects.requireNonNull(condition, "The condition cannot be null.");
        Objects.requireNonNull(batch, "The batch cannot be null.");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(MARKER, condition.marker());
        value.put(CRITERIA, encodeCriteria(condition.criteria()));
        value.put(EVENTS, batch.stream().map(DcbHistoryCodec::encodeEvent).toList());
        return Map.copyOf(value);
    }

    /**
     * Reads back the condition an append was attempted under.
     *
     * @param value the recorded value map
     * @return the condition
     */
    public static ModelAppendCondition decodeCondition(Map<String, Object> value) {
        Objects.requireNonNull(value, "The value cannot be null.");
        long marker = value.get(MARKER) instanceof Number number ? number.longValue() : DcbStoreModel.ORIGIN;
        return new ModelAppendCondition(marker, decodeCriteria(value.get(CRITERIA)));
    }

    /**
     * Reads back the batch an append offered.
     *
     * @param value the recorded value map
     * @return the events, in offer order
     */
    public static List<ModelEvent> decodeEvents(Map<String, Object> value) {
        Objects.requireNonNull(value, "The value cannot be null.");
        List<ModelEvent> events = new ArrayList<>();
        for (Object raw : asList(value.get(EVENTS))) {
            events.add(decodeEvent(asMap(raw)));
        }
        return List.copyOf(events);
    }

    /**
     * Renders an event.
     *
     * @param event the event to render
     * @return the rendered event
     */
    public static Map<String, Object> encodeEvent(ModelEvent event) {
        Objects.requireNonNull(event, "The event cannot be null.");
        return Map.of(ID, event.id(), TYPE, event.type(), TAGS, encodeTags(event.tags()));
    }

    /**
     * Reads back an event.
     *
     * @param value the rendered event
     * @return the event
     */
    public static ModelEvent decodeEvent(Map<String, Object> value) {
        Objects.requireNonNull(value, "The value cannot be null.");
        return new ModelEvent(String.valueOf(value.get(ID)), String.valueOf(value.get(TYPE)),
                              decodeTags(value.get(TAGS)));
    }

    /**
     * Renders a Dynamic Consistency Boundary.
     *
     * @param criteria the boundary's criteria
     * @return the rendered criteria
     */
    public static List<Map<String, Object>> encodeCriteria(Set<ModelCriterion> criteria) {
        Objects.requireNonNull(criteria, "The criteria cannot be null.");
        return criteria.stream()
                       .map(criterion -> Map.<String, Object>of(TAGS, encodeTags(criterion.tags()),
                                                                TYPES, List.copyOf(criterion.types())))
                       .toList();
    }

    /**
     * Reads back a Dynamic Consistency Boundary.
     *
     * @param raw the rendered criteria
     * @return the boundary's criteria; empty means match everything
     */
    public static Set<ModelCriterion> decodeCriteria(Object raw) {
        Set<ModelCriterion> criteria = new java.util.LinkedHashSet<>();
        for (Object element : asList(raw)) {
            Map<String, Object> criterion = asMap(element);
            Set<String> types = new java.util.LinkedHashSet<>();
            for (Object type : asList(criterion.get(TYPES))) {
                types.add(String.valueOf(type));
            }
            criteria.add(new ModelCriterion(types, decodeTags(criterion.get(TAGS))));
        }
        return Set.copyOf(criteria);
    }

    private static List<Map<String, Object>> encodeTags(Set<ModelTag> tags) {
        return tags.stream()
                   .map(tag -> Map.<String, Object>of(KEY, tag.key(), VALUE, tag.value()))
                   .toList();
    }

    private static Set<ModelTag> decodeTags(Object raw) {
        Set<ModelTag> tags = new java.util.LinkedHashSet<>();
        for (Object element : asList(raw)) {
            Map<String, Object> tag = asMap(element);
            tags.add(new ModelTag(String.valueOf(tag.get(KEY)), String.valueOf(tag.get(VALUE))));
        }
        return Set.copyOf(tags);
    }

    private static List<?> asList(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
