// tag::delivery-repository[]
package migration.paths.sagastoworkflows;

import javax.sql.DataSource;
import java.sql.SQLException;

public class DeliveryRepository {
    private final DataSource dataSource;

    public DeliveryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void record(String orderId, boolean delivered) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (var update = connection.prepareStatement(
                    "UPDATE deliveries SET delivered = ? WHERE order_id = ?")) {
                update.setBoolean(1, delivered);
                update.setString(2, orderId);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("Expected one delivery row for " + orderId);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not record delivery " + orderId, failure);
        }
    }
}
// end::delivery-repository[]
