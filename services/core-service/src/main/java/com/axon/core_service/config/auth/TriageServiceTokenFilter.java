package com.axon.core_service.config.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class TriageServiceTokenFilter extends OncePerRequestFilter {

    private final String serviceToken;

    public TriageServiceTokenFilter(@Value("${axon.triage.service-token:}") String serviceToken) {
        this.serviceToken = serviceToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader("X-Axon-Triage-Token");
        if (serviceToken != null && !serviceToken.isBlank()
                && supplied != null && constantTimeEquals(serviceToken, supplied)) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "ai-triage-service", null, List.of(new SimpleGrantedAuthority("ROLE_SYSTEM")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        byte[] left = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }
}
