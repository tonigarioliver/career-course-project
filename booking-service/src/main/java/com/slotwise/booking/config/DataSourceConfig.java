package com.slotwise.booking.config;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Wires two real connection pools — the primary (read-write) and the streaming replica
 * (read-only, {@code slotwise.datasource.replica.*}) — behind one
 * {@link ReplicationRoutingDataSource}, exposed as the single {@code @Primary DataSource}
 * bean the rest of the app (Hibernate, Flyway, actuator health) autowires.
 *
 * <p><b>The primary is built from a {@link JdbcConnectionDetails} bean, not straight off
 * {@code spring.datasource.*} properties.</b> {@code @ServiceConnection} on a Testcontainers
 * field (see {@code ReservationServiceIntegrationTest}) does not rewrite the
 * {@code spring.datasource.*} properties themselves — it registers its own
 * {@code JdbcConnectionDetails} bean, which only Boot's own {@code DataSourceAutoConfiguration}
 * normally consults. An earlier version of this class bound its own
 * {@code @ConfigurationProperties("spring.datasource")} bean directly, which reads the plain
 * application.yml defaults and completely bypasses that mechanism — in the integration test
 * this silently pointed the "primary" pool at the real docker-compose dev database instead of
 * the ephemeral Testcontainers one, so every write test was polluting dev data while its own
 * assertions read from the correctly-wired Testcontainers replica pool and saw nothing there.
 *
 * <p>{@link #fallbackPrimaryConnectionDetails()} below exists because Boot's own default
 * {@code JdbcConnectionDetails} bean is registered by {@code DataSourceAutoConfiguration},
 * which backs off entirely (as does any of its other beans) once a {@code DataSource} bean —
 * ours — already exists in the context. Outside a test with {@code @ServiceConnection}
 * (i.e. every real run of the app), nothing would otherwise provide one.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    // See class javadoc: only kicks in when nothing else (e.g. a test's @ServiceConnection)
    // has already supplied a JdbcConnectionDetails bean.
    @Bean
    @ConditionalOnMissingBean(JdbcConnectionDetails.class)
    JdbcConnectionDetails fallbackPrimaryConnectionDetails(final DataSourceProperties primaryDataSourceProperties) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return primaryDataSourceProperties.determineUsername();
            }

            @Override
            public String getPassword() {
                return primaryDataSourceProperties.determinePassword();
            }

            @Override
            public String getJdbcUrl() {
                return primaryDataSourceProperties.determineUrl();
            }
        };
    }

    @Bean
    DataSource primaryDataSource(final JdbcConnectionDetails connectionDetails) {
        return DataSourceBuilder.create()
                .url(connectionDetails.getJdbcUrl())
                .username(connectionDetails.getUsername())
                .password(connectionDetails.getPassword())
                .build();
    }

    @Bean
    @ConfigurationProperties("slotwise.datasource.replica")
    DataSourceProperties replicaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    DataSource replicaDataSource(final DataSourceProperties replicaDataSourceProperties) {
        return replicaDataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean
    @Primary
    DataSource dataSource(final DataSource primaryDataSource, final DataSource replicaDataSource) {
        final var routingDataSource = new ReplicationRoutingDataSource();
        routingDataSource.setTargetDataSources(Map.of(
                ReplicationRoutingDataSource.PRIMARY, primaryDataSource,
                ReplicationRoutingDataSource.REPLICA, replicaDataSource));
        routingDataSource.setDefaultTargetDataSource(primaryDataSource);
        routingDataSource.afterPropertiesSet();
        // See ReplicationRoutingDataSource's javadoc for why this wrapper isn't optional.
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
