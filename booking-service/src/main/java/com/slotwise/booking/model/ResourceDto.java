package com.slotwise.booking.model;

import lombok.Builder;

@Builder
public record ResourceDto(Long id, String name, String description, boolean active) {
}
