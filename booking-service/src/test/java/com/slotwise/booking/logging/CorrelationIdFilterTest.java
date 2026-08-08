package com.slotwise.booking.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void reusesInboundCorrelationIdAndEchoesItBack() throws Exception {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        final HttpServletResponse response = mock(HttpServletResponse.class);
        final FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn("given-id");

        filter.doFilterInternal(request, response, chain);

        verify(response).setHeader("X-Correlation-Id", "given-id");
        // cleared once the request finishes, so it can't leak into whatever this pooled
        // thread handles next
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void clearsMdcEvenWhenChainThrows() throws Exception {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        final HttpServletResponse response = mock(HttpServletResponse.class);
        final FilterChain chain = mock(FilterChain.class);
        doThrow(new RuntimeException("boom")).when(chain).doFilter(request, response);

        assertThrows(RuntimeException.class, () -> filter.doFilterInternal(request, response, chain));

        assertThat(MDC.get("correlationId")).isNull();
        verify(response).setHeader(eq("X-Correlation-Id"), anyString());
    }
}
