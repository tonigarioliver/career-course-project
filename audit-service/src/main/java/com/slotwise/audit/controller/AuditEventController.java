package com.slotwise.audit.controller;

import com.slotwise.audit.model.AuditEventDto;
import com.slotwise.audit.security.Roles;
import com.slotwise.audit.service.AuditEventService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuditEventController {

    private final AuditEventService auditEventService;

    @GetMapping("/api/audit-events")
    @PreAuthorize("hasRole('" + Roles.RESERVATION_ADMIN + "')")
    public Page<AuditEventDto> list(
            @RequestParam(required = false) @Nullable Long resourceId,
            @PageableDefault(size = 20) Pageable pageable) {
        return this.auditEventService.list(resourceId, pageable);
    }
}
