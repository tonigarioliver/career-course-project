package com.slotwise.booking.controller;

import com.slotwise.booking.model.CreateResourceRequest;
import com.slotwise.booking.model.ResourceDto;
import com.slotwise.booking.security.Roles;
import com.slotwise.booking.service.ResourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('" + Roles.RESERVATION_ADMIN + "')")
    public ResourceDto create(@Valid @RequestBody CreateResourceRequest request) {
        return this.resourceService.create(request);
    }

    @GetMapping
    public Page<ResourceDto> list(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return this.resourceService.list(pageable);
    }

    @GetMapping("/{id}")
    public ResourceDto getById(@PathVariable Long id) {
        return this.resourceService.getById(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('" + Roles.RESERVATION_ADMIN + "')")
    public ResourceDto update(@PathVariable Long id, @Valid @RequestBody CreateResourceRequest request) {
        return this.resourceService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('" + Roles.RESERVATION_ADMIN + "')")
    public void delete(@PathVariable Long id) {
        this.resourceService.delete(id);
    }
}
