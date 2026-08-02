package com.slotwise.booking.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record CreateResourceRequest(@NotBlank String name, String description) {
}
