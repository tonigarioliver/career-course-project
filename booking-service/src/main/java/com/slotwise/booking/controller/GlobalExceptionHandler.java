package com.slotwise.booking.controller;

import com.slotwise.booking.model.ApiErrorResponse;
import com.slotwise.booking.service.ReservationConflictException;
import com.slotwise.booking.service.ResourceNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        final var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage(),
                        (first, second) -> first));
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.builder().message("Validation failed").fieldErrors(fieldErrors).build());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        final var fieldErrors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        violation -> violation.getPropertyPath().toString(),
                        violation -> violation.getMessage(),
                        (first, second) -> first));
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.builder().message("Validation failed").fieldErrors(fieldErrors).build());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.builder().message(ex.getMessage()).build());
    }

    @ExceptionHandler(ReservationConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(ReservationConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.builder().message(ex.getMessage()).build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.builder().message(ex.getMessage()).build());
    }

    // A record's compact constructor (e.g. CreateReservationRequest's startTime/endTime check)
    // runs during Jackson's @RequestBody deserialization, before @Valid ever gets a chance to
    // run — so Spring wraps whatever it throws in HttpMessageNotReadableException instead of
    // letting it surface as the IllegalArgumentException handled above. Unwrap it here so both
    // paths return the same ApiErrorResponse shape.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        final var cause = ex.getMostSpecificCause();
        final var message = cause instanceof IllegalArgumentException ? cause.getMessage() : "Malformed request body";
        return ResponseEntity.badRequest().body(ApiErrorResponse.builder().message(message).build());
    }
}
