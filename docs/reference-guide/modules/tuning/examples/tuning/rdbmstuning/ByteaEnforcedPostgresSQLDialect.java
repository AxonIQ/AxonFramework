package tuning.rdbmstuning;

import java.sql.Types;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.BinaryJdbcType;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeRegistry;

// tag::bytea-enforced-postgresql-dialect[]
public class ByteaEnforcedPostgresSQLDialect extends PostgreSQLDialect {

    public ByteaEnforcedPostgresSQLDialect() {
        super(DatabaseVersion.make(9, 5));
    }

    @Override
    protected String columnType(int sqlTypeCode) {
        return sqlTypeCode == SqlTypes.BLOB ? "bytea" : super.columnType(sqlTypeCode);
    }

    @Override
    protected String castType(int sqlTypeCode) {
        return sqlTypeCode == SqlTypes.BLOB ? "bytea" : super.castType(sqlTypeCode);
    }

    @Override
    public void contributeTypes(TypeContributions typeContributions,
                                ServiceRegistry serviceRegistry) {
        super.contributeTypes(typeContributions, serviceRegistry);
        JdbcTypeRegistry jdbcTypeRegistry = typeContributions.getTypeConfiguration()
                                                             .getJdbcTypeRegistry();
        jdbcTypeRegistry.addDescriptor(Types.BLOB, BinaryJdbcType.INSTANCE);
    }
}
// end::bytea-enforced-postgresql-dialect[]
