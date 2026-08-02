package com.slotwise.booking.model;

import java.util.Map;
import lombok.Builder;

@Builder
public record ApiErrorResponse(String message, Map<String, String> fieldErrors) {
}
