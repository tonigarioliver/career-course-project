package com.slotwise.booking.service;

import com.slotwise.booking.data.Resource;
import com.slotwise.booking.data.ResourceRepository;
import com.slotwise.booking.model.CreateResourceRequest;
import com.slotwise.booking.model.ResourceDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
public class ResourceService {

    private static final String CACHE_NAME = "resources";

    private final ResourceRepository resourceRepository;
    private final ConversionService conversionService;
    private final CacheStampedeGuard cacheStampedeGuard;

    @Transactional
    public ResourceDto create(@NotNull @Valid CreateResourceRequest request) {
        final Resource resource = new Resource();
        resource.setName(request.name());
        resource.setDescription(request.description());
        resource.setActive(true);
        // ConversionService.convert() is @Nullable per Spring's contract (null only when the
        // source is null); this package is @NullMarked, so assert the non-null guarantee
        // explicitly instead of silently trusting it.
        return Objects.requireNonNull(
                this.conversionService.convert(this.resourceRepository.save(resource), ResourceDto.class));
    }

    // Cache-Aside with cross-instance stampede protection (see CacheStampedeGuard): a hit
    // returns straight from Redis; a miss loads under a Redis-backed lock shared by every
    // instance, so a TTL expiry can't send every instance's first-through request to
    // Postgres at once. delete() below evicts (nothing to put); update() below writes
    // through instead — either way a stale entry can't outlive the TTL (see
    // application.yml spring.cache.redis.time-to-live) even if a write path were ever
    // missed. Not @Cacheable(sync = true): that only dedupes callers within one JVM, and
    // its cache-put happens after the annotated method returns — after the loader would
    // have already released a hand-rolled lock — so it can't be composed with one safely.
    @Transactional(readOnly = true)
    public ResourceDto getById(@NotNull Long id) {
        return this.cacheStampedeGuard.getOrLoad(CACHE_NAME, id, ResourceDto.class, () -> this.findSummaryOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<ResourceDto> list(Pageable pageable) {
        return this.resourceRepository.findAllSummaries(pageable);
    }

    // Write-Through: the new DTO is written into Redis in this same call (not just evicted),
    // so a read right after update() never has to fall back to the DB to see it.
    @CachePut(value = CACHE_NAME, key = "#id")
    @Transactional
    public ResourceDto update(@NotNull Long id, @NotNull @Valid CreateResourceRequest request) {
        final Resource resource = this.findOrThrow(id);
        resource.setName(request.name());
        resource.setDescription(request.description());
        return Objects.requireNonNull(this.conversionService.convert(resource, ResourceDto.class));
    }

    @CacheEvict(value = CACHE_NAME, key = "#id")
    @Transactional
    public void delete(@NotNull Long id) {
        if (!this.resourceRepository.existsById(id)) {
            throw new ResourceNotFoundException(id);
        }
        this.resourceRepository.deleteById(id);
    }

    private Resource findOrThrow(Long id) {
        return this.resourceRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(id));
    }

    private ResourceDto findSummaryOrThrow(Long id) {
        return this.resourceRepository.findSummaryById(id).orElseThrow(() -> new ResourceNotFoundException(id));
    }
}
