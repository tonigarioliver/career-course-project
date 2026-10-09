package com.slotwise.booking.service;

import com.slotwise.events.ReservationCreatedEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.stream.Stream;
import com.slotwise.booking.data.OutboxEventRepository;
import com.slotwise.booking.model.Caller;
import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.CreateResourceRequest;
import com.slotwise.booking.model.ReservationDto;
import com.slotwise.booking.model.ResourceDto;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import static org.awaitility.Awaitility.await;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReservationServiceIntegrationTest {

    // Tests that don't care about the caller act as an admin, who may name any owner.
    private static final Caller ADMIN = new Caller("test-admin", true);

    @TestConfiguration
    static class ConversionServiceTestConfig {
        @Bean
        ConversionService conversionService(List<Converter<?, ?>> converters) {
            final DefaultConversionService conversionService = new DefaultConversionService();
            converters.forEach(conversionService::addConverter);
            return conversionService;
        }
    }

    // A second, independent consumer group reading "reservation-events" — proof that Kafka
    // consumer groups are genuinely independent (see decisions.md): this group and the
    // production ReservationEventListener's ("booking-service", from application.yml) both
    // get every message, neither steals it from the other, unlike a single Redis Pub/Sub
    // subscriber list.
    @TestConfiguration
    static class CapturingListenerConfig {
        @Bean
        CapturingListener capturingListener() {
            return new CapturingListener();
        }
    }

    static class CapturingListener {
        final BlockingQueue<ReservationCreatedEvent> received = new LinkedBlockingQueue<>();

        @KafkaListener(topics = ReservationCreatedEvent.TOPIC, groupId = "test-consumer")
        void onReservationCreated(final ReservationCreatedEvent event) {
            this.received.add(event);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    // ResourceService (used to create resources below) now needs a real RedissonClient
    // bean (Fase 3 stampede guard) — without this, RedissonConfig would eagerly try to
    // connect to the application.yml default of localhost:6379 and fail.
    @Container
    @ServiceConnection("redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    // Fase 4 "Kafka" kickoff — same image as docker-compose.yml's KRaft single-broker setup,
    // so this test exercises the same broker behavior as the real dev environment.
    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

    // @ServiceConnection above only wires spring.datasource.* (what DataSourceConfig's
    // primaryDataSource reads) — it has no idea about slotwise.datasource.replica.*, our
    // own property prefix, so without this a @Transactional(readOnly = true) call (e.g.
    // listByResource) would try the real localhost:5433 default and fail outside an
    // environment that happens to have a docker-compose replica running. There's no real
    // standby to test replication itself here (that's verified by hand against the actual
    // docker-compose primary+replica) — this test is about ReservationService's logic, so
    // pointing "replica" at the same Testcontainers instance is the correct test double.
    @DynamicPropertySource
    static void replicaDataSourceProperties(final DynamicPropertyRegistry registry) {
        registry.add("slotwise.datasource.replica.url", postgres::getJdbcUrl);
        registry.add("slotwise.datasource.replica.username", postgres::getUsername);
        registry.add("slotwise.datasource.replica.password", postgres::getPassword);
    }

    private final ResourceService resourceService;
    private final ReservationService reservationService;
    private final ReservationWriteService reservationWriteService;
    private final CapturingListener capturingListener;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEvents processedEvents;
    private final JdbcClient jdbcClient;

    @Autowired
    ReservationServiceIntegrationTest(
            ResourceService resourceService, ReservationService reservationService, ReservationWriteService reservationWriteService, CapturingListener capturingListener,
            OutboxEventRepository outboxEventRepository, ProcessedEvents processedEvents, JdbcClient jdbcClient) {
        this.processedEvents = processedEvents;
        this.jdbcClient = jdbcClient;
        this.outboxEventRepository = outboxEventRepository;
        this.resourceService = resourceService;
        this.reservationService = reservationService;
        this.reservationWriteService = reservationWriteService;
        this.capturingListener = capturingListener;
    }

    @Test
    void create_publishesReservationCreatedEvent() {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room Kafka").build());

        final ReservationDto reservation = this.reservationService.create(ADMIN, CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(Instant.parse("2026-02-01T10:00:00Z"))
                .endTime(Instant.parse("2026-02-01T11:00:00Z"))
                .ownerSubject("user-kafka")
                .build());

        // CapturingListener's queue is one shared bean for the whole test class, and every
        // other @Test method in here also calls create() (each publishing its own event to the
        // same topic) — so this drains past whatever unrelated events are already queued up
        // from other test methods instead of assuming the very next one is ours.
        final ReservationCreatedEvent event = pollForReservation(reservation.id());
        assertThat(event.resourceId()).isEqualTo(resource.id());
        assertThat(event.ownerSubject()).isEqualTo("user-kafka");

        // The relay marks the row published right after the broker ack, so it can lag the
        // consumer seeing the message by a moment.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(this.outboxEventRepository.findAll())
                        .anyMatch(row -> row.getMessageKey().equals(resource.id().toString())
                                && row.getPublishedAt() != null));
    }

    // A record that can't be deserialized must not wedge the consumers: without ErrorHandlingDeserializer the
    // container retries it forever and no later event on that partition is ever delivered.
    @Test
    void poisonMessage_doesNotBlockLaterEvents() throws Exception {
        final var rawFactory = new DefaultKafkaProducerFactory<String, byte[]>(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()), new StringSerializer(), new ByteArraySerializer());
        try {
            // .get(): the poison must be on the topic before the valid event is published.
            new KafkaTemplate<>(rawFactory).send(ReservationCreatedEvent.TOPIC, "poison-key", "not-json".getBytes()).get();
        } finally {
            rawFactory.destroy();
        }

        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room Poison").build());
        final ReservationDto reservation = this.reservationService.create(ADMIN, CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(Instant.parse("2026-05-01T10:00:00Z"))
                .endTime(Instant.parse("2026-05-01T11:00:00Z"))
                .ownerSubject("user-poison")
                .build());

        assertThat(this.pollForReservation(reservation.id()).resourceId()).isEqualTo(resource.id());
    }

    // The production ReservationEventListener marks every event it handles; this proves it ran
    // end to end (Kafka -> listener -> transactional dedup insert).
    @Test
    void create_listenerMarksEventProcessed() {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room Dedup").build());
        final ReservationDto reservation = this.reservationService.create(ADMIN, CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(Instant.parse("2026-04-01T10:00:00Z"))
                .endTime(Instant.parse("2026-04-01T11:00:00Z"))
                .ownerSubject("user-dedup")
                .build());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(this.jdbcClient.sql("SELECT count(*) FROM processed_event WHERE id = :id")
                        .param("id", "reservation-created:" + reservation.id())
                        .query(Long.class).single()).isEqualTo(1L));
    }

    @Test
    void markProcessed_secondCallForSameId_isDuplicate() {
        assertThat(this.processedEvents.markProcessed("test-event-1")).isTrue();
        assertThat(this.processedEvents.markProcessed("test-event-1")).isFalse();
    }

    // The outbox row is written in the reservation's transaction, so a rolled-back create
    // must not leave an event behind.
    @Test
    void create_conflict_leavesNoOutboxRow() {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room Outbox").build());
        final CreateReservationRequest request = CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(Instant.parse("2026-03-01T10:00:00Z"))
                .endTime(Instant.parse("2026-03-01T11:00:00Z"))
                .ownerSubject("user-outbox")
                .build();
        this.reservationService.create(ADMIN, request);

        assertThatThrownBy(() -> this.reservationService.create(ADMIN, request))
                .isInstanceOf(ReservationConflictException.class);

        assertThat(this.outboxEventRepository.findAll())
                .filteredOn(row -> row.getMessageKey().equals(resource.id().toString()))
                .hasSize(1);
    }

    // Stream.generate(this::pollNext) keeps calling poll(10s) for as long as a message keeps
    // arriving (takeWhile stops on the first null, i.e. the first 10s with nothing new);
    // filter/findFirst then picks out the one that's actually ours among whatever unrelated
    // events other @Test methods left in the shared queue.
    private ReservationCreatedEvent pollForReservation(final Long reservationId) {
        return Stream.generate(this::pollNext)
                .takeWhile(Objects::nonNull)
                .filter(event -> event.reservationId().equals(reservationId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No reservation-events message for reservation " + reservationId));
    }

    private ReservationCreatedEvent pollNext() {
        try {
            return this.capturingListener.received.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while polling reservation-events", e);
        }
    }

    private ReservationDto bookAs(final Caller caller, final String ownerSubject, final int hour) {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room Owner " + hour).build());
        return this.reservationService.create(caller, CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(Instant.parse("2026-06-01T00:00:00Z").plusSeconds(hour * 3600L))
                .endTime(Instant.parse("2026-06-01T00:00:00Z").plusSeconds(hour * 3600L + 3600))
                .ownerSubject(ownerSubject)
                .build());
    }

    // The owner rule lives in the service: the owner is the caller unless an admin names someone else.
    @Test
    void create_omittedOwner_defaultsToCaller() {
        assertThat(this.bookAs(new Caller("sub-bob", false), null, 1).ownerSubject()).isEqualTo("sub-bob");
    }

    @Test
    void create_blankOwner_defaultsToCaller() {
        assertThat(this.bookAs(new Caller("sub-bob", false), "  ", 2).ownerSubject()).isEqualTo("sub-bob");
    }

    @Test
    void create_ownerEqualToCaller_isAccepted() {
        assertThat(this.bookAs(new Caller("sub-bob", false), "sub-bob", 3).ownerSubject()).isEqualTo("sub-bob");
    }

    // The orchestrator always fills the owner in; the transactional write still refuses an ownerless request
    // from anyone calling the write service directly.
    @Test
    void writeService_withoutOwner_isRejected() {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room Ownerless").build());

        assertThatThrownBy(() -> this.reservationWriteService.create(CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(Instant.parse("2026-07-01T10:00:00Z"))
                .endTime(Instant.parse("2026-07-01T11:00:00Z"))
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerSubject");
    }

    @Test
    void create_nonAdminNamingAnotherOwner_isRejected() {
        assertThatThrownBy(() -> this.bookAs(new Caller("sub-bob", false), "sub-alice", 4))
                .isInstanceOf(ReservationOwnerNotAllowedException.class);
    }

    @Test
    void create_adminNamingAnotherOwner_isAllowed() {
        assertThat(this.bookAs(new Caller("sub-alice", true), "sub-bob", 5).ownerSubject()).isEqualTo("sub-bob");
    }

    @Test
    void create_overlappingReservation_throwsConflict() {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room A").build());

        final Instant start = Instant.parse("2026-01-01T10:00:00Z");
        final Instant end = Instant.parse("2026-01-01T11:00:00Z");

        this.reservationService.create(ADMIN, CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start)
                .endTime(end)
                .ownerSubject("user-1")
                .build());

        final CreateReservationRequest overlapping = CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start.plusSeconds(1800))
                .endTime(end.plusSeconds(1800))
                .ownerSubject("user-2")
                .build();

        assertThatThrownBy(() -> this.reservationService.create(ADMIN, overlapping))
                .isInstanceOf(ReservationConflictException.class);
    }

    @Test
    void create_backToBackReservation_succeeds() {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room B").build());

        final Instant start = Instant.parse("2026-01-02T10:00:00Z");
        final Instant end = Instant.parse("2026-01-02T11:00:00Z");

        this.reservationService.create(ADMIN, CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start)
                .endTime(end)
                .ownerSubject("user-1")
                .build());

        final ReservationDto backToBack = this.reservationService.create(ADMIN, CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(end)
                .endTime(end.plusSeconds(3600))
                .ownerSubject("user-2")
                .build());

        assertThat(backToBack.id()).isNotNull();
    }

    @Test
    void create_concurrentOverlappingReservations_onlyOneSucceeds() throws Exception {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room C").build());
        final Instant start = Instant.parse("2026-01-03T10:00:00Z");
        final Instant end = Instant.parse("2026-01-03T11:00:00Z");

        final Callable<ReservationDto> createA = () -> this.reservationService.create(ADMIN, CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start)
                .endTime(end)
                .ownerSubject("user-A")
                .build());
        final Callable<ReservationDto> createB = () -> this.reservationService.create(ADMIN, CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start.plusSeconds(1800))
                .endTime(end.plusSeconds(1800))
                .ownerSubject("user-B")
                .build());

        // No sleep/latch needed to force the race: findByIdForUpdate's row lock serializes
        // the two create() calls on its own — whichever thread loses the race for the lock
        // simply blocks until the winner commits, then sees its reservation and conflicts.
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Future<ReservationDto> futureA = executor.submit(createA);
            final Future<ReservationDto> futureB = executor.submit(createB);

            final int failures = countConflictFailures(futureA) + countConflictFailures(futureB);
            assertThat(failures).isEqualTo(1);
        } finally {
            executor.shutdown();
        }

        assertThat(this.reservationService.listByResource(resource.id(), Pageable.unpaged())
                .getTotalElements()).isEqualTo(1);
    }

    private static int countConflictFailures(Future<ReservationDto> future) throws InterruptedException {
        try {
            future.get();
            return 0;
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(ReservationConflictException.class);
            return 1;
        }
    }
}
