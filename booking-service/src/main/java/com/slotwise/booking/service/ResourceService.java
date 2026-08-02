package com.slotwise.booking.service;

import com.slotwise.booking.data.Resource;
import com.slotwise.booking.data.ResourceRepository;
import com.slotwise.booking.model.CreateResourceRequest;
import com.slotwise.booking.model.ResourceDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final ConversionService conversionService;

    @Transactional
    public ResourceDto create(CreateResourceRequest request) {
        Resource resource = new Resource();
        resource.setName(request.name());
        resource.setDescription(request.description());
        resource.setActive(true);
        return conversionService.convert(resourceRepository.save(resource), ResourceDto.class);
    }

    @Transactional(readOnly = true)
    public ResourceDto getById(Long id) {
        return conversionService.convert(findOrThrow(id), ResourceDto.class);
    }

    @Transactional(readOnly = true)
    public Page<ResourceDto> list(Pageable pageable) {
        return resourceRepository.findAll(pageable).map(r -> conversionService.convert(r, ResourceDto.class));
    }

    @Transactional
    public ResourceDto update(Long id, CreateResourceRequest request) {
        Resource resource = findOrThrow(id);
        resource.setName(request.name());
        resource.setDescription(request.description());
        return conversionService.convert(resource, ResourceDto.class);
    }

    @Transactional
    public void delete(Long id) {
        if (!resourceRepository.existsById(id)) {
            throw new ResourceNotFoundException(id);
        }
        resourceRepository.deleteById(id);
    }

    private Resource findOrThrow(Long id) {
        return resourceRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(id));
    }
}
