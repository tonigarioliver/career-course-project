package com.slotwise.booking.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Routes every {@code getConnection()} call to the replica when it happens inside a
 * {@code @Transactional(readOnly = true)} method, and to the primary otherwise (including
 * outside any transaction at all — Flyway migrations, startup, etc.).
 *
 * <p>{@code AbstractRoutingDataSource} (spring-jdbc, already on the classpath transitively
 * via spring-boot-starter-data-jpa — no new dependency) is Spring's own building block for
 * exactly this: a {@code DataSource} that isn't a real connection pool itself, just a
 * dispatcher over a {@code Map<lookupKey, DataSource>} of real ones, re-evaluating
 * {@link #determineCurrentLookupKey()} on every connection request.
 *
 * <p><b>Must be wrapped in a {@code LazyConnectionDataSourceProxy}</b> (see
 * {@link DataSourceConfig}) — not optional. {@code AbstractPlatformTransactionManager}
 * calls {@code doBegin()} (which asks the DataSource for a real connection) <em>before</em>
 * {@code prepareSynchronization()} (which is what actually sets the read-only flag this
 * class reads). Routing directly against this class would see last transaction's flag, or
 * none — the lazy proxy defers the real {@code getConnection()} call until the first
 * statement actually runs, by which point the flag is already set.
 */
public class ReplicationRoutingDataSource extends AbstractRoutingDataSource {

    static final String PRIMARY = "primary";
    static final String REPLICA = "replica";

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? REPLICA : PRIMARY;
    }
}
