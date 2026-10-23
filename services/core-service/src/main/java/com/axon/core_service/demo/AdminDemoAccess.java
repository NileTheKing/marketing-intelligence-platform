package com.axon.core_service.demo;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;

public final class AdminDemoAccess {
    public static final String AUTHORITY = "ROLE_DEMO";
    public static final String COOKIE_NAME = "adminDemoToken";
    private static final Set<String> PAGES = Set.of(
            "/", "/shop", "/demo/admin", "/demo/exit", "/admin", "/admin/campaigns",
            "/admin/coupons", "/admin/events", "/admin/events/event-board.js", "/admin/dashboard/overview", "/admin/incrementality");
    private static final Pattern READ_API = Pattern.compile(
            "/api/v1/(?:campaigns(?:/\\d+(?:/activities)?)?"
            + "|campaign-activities(?:/\\d+|/count)?|coupons(?:/\\d+)?|events(?:/\\d+|/active)?"
            + "|dashboard/(?:overview|(?:activity|campaign)/\\d+|cohort/activity/\\d+"
            + "|campaign-runs/\\d+/incrementality|stream/(?:activity|campaign)/\\d+))");
    private static final Pattern DASHBOARD_PAGE = Pattern.compile(
            "/admin/(?:dashboard/(?:\\d+|cohort/\\d+|campaign/\\d+)|campaign-runs/\\d+/incrementality)");

    private AdminDemoAccess() {
    }

    public static boolean isDemo(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> AUTHORITY.equals(authority.getAuthority()));
    }

    public static boolean canRead(HttpServletRequest request) {
        if (!Set.of("GET", "HEAD").contains(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PAGES.contains(path) || READ_API.matcher(path).matches() || DASHBOARD_PAGE.matcher(path).matches()
                || path.startsWith("/js/") || path.startsWith("/css/")
                || path.startsWith("/image/") || path.startsWith("/images/") || path.equals("/favicon.ico");
    }
}
