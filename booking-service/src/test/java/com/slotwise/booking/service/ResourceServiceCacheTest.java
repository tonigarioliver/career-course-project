package com.slotwise.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.slotwise.booking.data.ResourceRepository;
import com.slotwise.booking.model.CreateResourceRequest;
import com.slotwise.booking.model.ResourceDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// Verifies the caching wiring end to end (real Redis via Testcontainers, not a mock
// cache): first getById() is a Cache-Aside miss that hits the repository, second is
// served from Redis without touching it; update() is Write-Through, so the DTO it
// returns is already what a following getById() serves, again without a repository hit.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ResourceServiceCacheTest {

    @TestConfiguration
    static class ConversionServiceTestConfig {
        @Bean
        ConversionService conversionService(List<Converter<?, ?>> converters) {
            final DefaultConversionService conversionService = new DefaultConversionService();
            converters.forEach(conversionService::addConverter);
            return conversionService;
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    @ServiceConnection("redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    // See ReservationServiceIntegrationTest for why: @ServiceConnection only wires
    // spring.datasource.*, not our own slotwise.datasource.replica.* prefix that
    // getById()'s @Transactional(readOnly = true) routes through.
    @DynamicPropertySource
    static void replicaDataSourceProperties(final DynamicPropertyRegistry registry) {
        registry.add("slotwise.datasource.replica.url", postgres::getJdbcUrl);
        registry.add("slotwise.datasource.replica.username", postgres::getUsername);
        registry.add("slotwise.datasource.replica.password", postgres::getPassword);
    }

    @Autowired
    private ResourceService resourceService;

    @MockitoSpyBean
    private ResourceRepository resourceRepository;

    @Test
    void getById_secondCall_servedFromCacheNotRepository() {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Cached Room").build());
        Mockito.clearInvocations(this.resourceRepository);

        final ResourceDto first = this.resourceService.getById(resource.id());
        final ResourceDto second = this.resourceService.getById(resource.id());

        assertThat(first).isEqualTo(second);
        verify(this.resourceRepository, Mockito.times(1)).findSummaryById(resource.id());
    }

    @Test
    void update_writesThroughToCache_soNextGetByIdSkipsRepository() {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Original Room").build());
        this.resourceService.getById(resource.id());

        final ResourceDto updated = this.resourceService.update(resource.id(),
                CreateResourceRequest.builder().name("Renamed Room").build());
        Mockito.clearInvocations(this.resourceRepository);

        final ResourceDto afterUpdate = this.resourceService.getById(resource.id());

        assertThat(afterUpdate).isEqualTo(updated);
        assertThat(afterUpdate.name()).isEqualTo("Renamed Room");
        verify(this.resourceRepository, Mockito.never()).findSummaryById(resource.id());
    }
}
