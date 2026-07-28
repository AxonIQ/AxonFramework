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

import org.axonframework.hunt.model.DcbStoreModel;
import org.axonframework.hunt.model.ModelAppendCondition;
import org.axonframework.hunt.model.ModelCriterion;
import org.axonframework.hunt.model.ModelEvent;
import org.axonframework.hunt.model.ModelTag;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays every append decision the TLA+ generator printed through the executable reference model, and reports
 * whether the two agree.
 * <p>
 * Not part of the Maven reactor and not a test. It is the mechanical half of the cross-check: the specification and
 * the reference model are supposed to encode the same rules, and the only way to know is to make both decide the same
 * finite set of cases and compare. Run it as described in ../README.md, "Cross-check".
 * <p>
 * The event and boundary pools below MUST mirror the pools in DcbRules.tla. If either side changes, this program
 * reports a disagreement on the cases the change touches, which is the intended alarm rather than a maintenance
 * hazard.
 */
public final class CrossCheck {

    /**
     * The event pool, index 1..3, mirroring MCEvents in DcbRules.tla.
     */
    private static final List<ModelEvent> EVENTS = List.of(
            new ModelEvent("e-1", "e1", Set.of(tag("a"))),
            new ModelEvent("e-2", "e2", Set.of(tag("a"), tag("b"))),
            new ModelEvent("e-3", "e1", Set.of(tag("b")))
    );

    /**
     * The boundary pool, index 1..4, mirroring MCBoundaries in DcbRules.tla. Index 1 is the empty boundary.
     */
    private static final List<Set<ModelCriterion>> BOUNDARIES = List.of(
            Set.of(),
            Set.of(ModelCriterion.havingTags(tag("a"))),
            Set.of(ModelCriterion.havingTags(tag("a"), tag("b"))),
            Set.of(ModelCriterion.havingTagsAndTypes(Set.of(tag("a")), Set.of("e1")),
                   ModelCriterion.havingTagsAndTypes(Set.of(tag("b")), Set.of()))
    );

    /**
     * The store width the generator pads to, which is the MaxLen in the cfg.
     */
    private static final int MAX_LEN = 3;

    /**
     * The marker the generator prints for INFINITY, which is MaxLen + 1 there and Long.MAX_VALUE here.
     */
    private static final long TLA_INFINITY = MAX_LEN + 1;

    private static final Pattern INTEGER = Pattern.compile("-?\\d+");

    private CrossCheck() {
    }

    public static void main(String[] args) throws Exception {
        Set<String> cases = new LinkedHashSet<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("<<<<")) {
                    cases.add(line.trim());
                }
            }
        }
        if (cases.isEmpty()) {
            System.err.println("No case lines on stdin. Expected lines starting with '<<<<'.");
            System.exit(2);
        }

        int agreed = 0;
        int disagreed = 0;
        for (String one : cases) {
            long[] field = integersOf(one);
            if (field.length != MAX_LEN + 3) {
                System.err.println("Unparseable case: " + one);
                System.exit(2);
            }
            DcbStoreModel model = new DcbStoreModel();
            for (int position = 0; position < MAX_LEN; position++) {
                int eventIndex = (int) field[position];
                if (eventIndex != 0) {
                    // Anchored at INFINITY so that populating the store cannot itself be rejected.
                    model.append(ModelAppendCondition.none(), List.of(EVENTS.get(eventIndex - 1)));
                }
            }
            long tlaMarker = field[MAX_LEN];
            int boundaryIndex = (int) field[MAX_LEN + 1];
            boolean tlaAccepted = field[MAX_LEN + 2] == 1;

            long javaMarker = tlaMarker == TLA_INFINITY ? DcbStoreModel.INFINITY : tlaMarker;
            boolean javaAccepted = model.wouldAccept(
                    new ModelAppendCondition(javaMarker, BOUNDARIES.get(boundaryIndex - 1)));

            if (javaAccepted == tlaAccepted) {
                agreed++;
            } else {
                disagreed++;
                System.out.println("DISAGREE " + one
                                           + "  tla=" + tlaAccepted + " java=" + javaAccepted
                                           + "  store=" + model.events().stream().map(ModelEvent::id).toList());
            }
        }

        System.out.println("cases=" + cases.size() + " agreed=" + agreed + " disagreed=" + disagreed);
        System.exit(disagreed == 0 ? 0 : 1);
    }

    private static long[] integersOf(String line) {
        Matcher matcher = INTEGER.matcher(line);
        long[] found = new long[MAX_LEN + 3];
        int count = 0;
        while (matcher.find() && count < found.length) {
            found[count++] = Long.parseLong(matcher.group());
        }
        return count == found.length && !matcher.find() ? found : new long[0];
    }

    private static ModelTag tag(String value) {
        return ModelTag.of("k", value);
    }
}
