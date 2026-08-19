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
package migration.paths.tokenstore;

// tag::populate-token-store-mask-migration[]
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class V2__PopulateTokenStoreMask extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        Map<String, List<Integer>> segmentsByProcessor = readSegments(conn);
        updateMasks(conn, segmentsByProcessor);
    }

    private Map<String, List<Integer>> readSegments(Connection conn) throws SQLException {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT processorName, segment FROM token_entry ORDER BY processorName, segment");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.computeIfAbsent(rs.getString("processorName"), k -> new ArrayList<>())
                      .add(rs.getInt("segment"));
            }
        }
        return result;
    }

    private void updateMasks(Connection conn,
                             Map<String, List<Integer>> segmentsByProcessor) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE token_entry SET mask = ? WHERE processorName = ? AND segment = ?")) {
            for (Map.Entry<String, List<Integer>> entry : segmentsByProcessor.entrySet()) {
                String processorName = entry.getKey();
                int[] ids = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
                for (SegmentInfo seg : computeSegments(ids)) {
                    stmt.setInt(1, seg.mask());
                    stmt.setString(2, processorName);
                    stmt.setInt(3, seg.segmentId());
                    stmt.addBatch();
                }
            }
            stmt.executeBatch();
        }
    }

    private static List<SegmentInfo> computeSegments(int[] segmentIds) {
        if (segmentIds.length == 0) {
            return List.of();
        }
        List<Integer> ids = Arrays.stream(segmentIds).boxed().toList();
        Set<SegmentInfo> result = new HashSet<>();
        computeSegments(new SegmentInfo(0, 0), ids, result);
        return result.stream()
                     .sorted(Comparator.comparingInt(SegmentInfo::segmentId))
                     .toList();
    }

    private static void computeSegments(SegmentInfo current, List<Integer> segmentIds,
                                        Set<SegmentInfo> result) {
        int newMask = (current.mask() << 1) | 1;
        int sibling = current.segmentId() + (current.mask() == 0 ? 1 : newMask ^ current.mask());
        if (segmentIds.contains(sibling)) {
            computeSegments(new SegmentInfo(current.segmentId(), newMask), segmentIds, result);
            computeSegments(new SegmentInfo(sibling, newMask), segmentIds, result);
        } else {
            result.add(current);
        }
    }

    private record SegmentInfo(int segmentId, int mask) {}
}
// end::populate-token-store-mask-migration[]
