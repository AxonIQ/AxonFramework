package migration.paths.sagastoworkflows;

import org.h2.jdbcx.JdbcDataSource;
import java.sql.SQLException;
import java.util.UUID;

final class DeliveryDatabase {
    private final JdbcDataSource dataSource = new JdbcDataSource();
    final DeliveryRepository repository;

    DeliveryDatabase() {
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE deliveries (order_id VARCHAR(100) PRIMARY KEY, delivered BOOLEAN)");
            statement.execute("INSERT INTO deliveries (order_id) VALUES ('o1')");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        repository = new DeliveryRepository(dataSource);
    }

    Boolean delivered() {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT delivered FROM deliveries WHERE order_id = 'o1'")) {
            if (!rows.next()) throw new AssertionError("Delivery row missing");
            return (Boolean) rows.getObject(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
