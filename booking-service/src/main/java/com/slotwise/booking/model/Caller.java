package com.slotwise.booking.model;

// Who is making a request, as the domain needs to know it: the authenticated subject and whether they are an
// admin. Built by the controller from the security context, so services never depend on Spring Security.
public record Caller(String subject, boolean admin) {
}
