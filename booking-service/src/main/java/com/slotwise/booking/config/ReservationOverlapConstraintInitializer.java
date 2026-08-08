package com.slotwise.booking.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Adds the {@code reservations_no_overlap} EXCLUDE constraint on first boot against a fresh
 * database — Hibernate's {@code ddl-auto=update} has no JPA annotation for exclusion
 * constraints, so this can't be expressed on the {@code Reservation} entity. Runs raw JDBC
 * (not Spring Boot's schema.sql mechanism) because its script splitter breaks on the
 * dollar-quoted {@code DO $$ ... $$} block below — it naively splits on every {@code ;},
 * including the ones inside the block. See decisions.md "Indexing findOverlapping" for the
 * full rationale (the race condition this constraint closes) and why {@code btree_gist} is
 * needed to combine a plain equality column with a range-overlap column in one GiST index.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationOverlapConstraintInitializer implements ApplicationRunner {

    private static final String SQL = """
            DO $$
            BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'reservations_no_overlap') THEN
                    ALTER TABLE reservations
                        ADD CONSTRAINT reservations_no_overlap
                        EXCLUDE USING gist (
                            resource_id WITH =,
                            tstzrange(start_time, end_time) WITH &&
                        ) WHERE (status <> 'CANCELLED');
                END IF;
            END $$;
            """;

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        try (Connection connection = this.dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");
            statement.execute(SQL);
        }
        log.info("Ensured reservations_no_overlap EXCLUDE constraint exists");
    }
}
