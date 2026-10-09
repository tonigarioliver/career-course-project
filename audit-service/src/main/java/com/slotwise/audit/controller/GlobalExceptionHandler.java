package com.slotwise.audit.controller;

import com.slotwise.audit.model.ApiErrorResponse;
import org.hibernate.query.sqm.PathElementException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // A client-supplied ?sort=<property> is appended to the JPQL ORDER BY; an unknown property
    // makes Hibernate throw, which Spring Data wraps as InvalidDataAccessApiUsageException. Only
    // that case is the client's fault (400) — any other data-access usage error is a server bug,
    // so it is rethrown and stays a 500 instead of being masked as a bad request.
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<ApiErrorResponse> handleBadSort(final InvalidDataAccessApiUsageException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof PathElementException) {
                return ResponseEntity.badRequest().body(new ApiErrorResponse("Unknown sort property"));
            }
        }
        throw ex;
    }
}
