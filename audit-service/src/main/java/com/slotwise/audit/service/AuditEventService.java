package com.slotwise.audit.service;

import com.slotwise.audit.data.AuditEventRepository;
import com.slotwise.audit.model.AuditEventDto;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditEventRepository auditEventRepository;

    @Transactional(readOnly = true)
    public Page<AuditEventDto> list(@Nullable Long resourceId, @NotNull Pageable pageable) {
        return this.auditEventRepository.findSummaries(resourceId, pageable);
    }
}
