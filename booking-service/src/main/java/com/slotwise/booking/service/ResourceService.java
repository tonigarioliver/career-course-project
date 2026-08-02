package com.slotwise.booking.service;

import com.slotwise.booking.data.Resource;
import com.slotwise.booking.data.ResourceRepository;
import com.slotwise.booking.model.CreateResourceRequest;
import com.slotwise.booking.model.ResourceDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
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

    private final ResourceRepository resourceRepository;
    private final ConversionService conversionService;

    @Transactional
    public ResourceDto create(@NotNull @Valid CreateResourceRequest request) {
        final Resource resource = new Resource();
        resource.setName(request.name());
        resource.setDescription(request.description());
        resource.setActive(true);
        return this.conversionService.convert(this.resourceRepository.save(resource), ResourceDto.class);
    }

    @Transactional(readOnly = true)
    public ResourceDto getById(@NotNull Long id) {
        return this.resourceRepository.findSummaryById(id).orElseThrow(() -> new ResourceNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ResourceDto> list(Pageable pageable) {
        return this.resourceRepository.findAllSummaries(pageable);
    }

    @Transactional
    public ResourceDto update(@NotNull Long id, @NotNull @Valid CreateResourceRequest request) {
        final Resource resource = this.findOrThrow(id);
        resource.setName(request.name());
        resource.setDescription(request.description());
        return this.conversionService.convert(resource, ResourceDto.class);
    }

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
}
