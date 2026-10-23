package com.axon.core_service.demo;

import com.axon.core_service.config.auth.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class AdminDemoController {
    private final JwtTokenProvider jwtTokenProvider;

    @GetMapping("/demo/admin")
    public String enter(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            return "redirect:/admin";
        }
        Authentication demo = new UsernamePasswordAuthenticationToken("0", "",
                List.of(new SimpleGrantedAuthority(AdminDemoAccess.AUTHORITY)));
        String token = jwtTokenProvider.generateAccessToken(demo);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, token, Duration.ofMinutes(30)).toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return "redirect:/admin";
    }

    @GetMapping("/demo/exit")
    public String exit(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO).toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return "redirect:/shop";
    }

    private ResponseCookie cookie(HttpServletRequest request, String value, Duration maxAge) {
        return ResponseCookie.from(AdminDemoAccess.COOKIE_NAME, value).httpOnly(true)
                .secure(request.isSecure()).sameSite("Lax").path("/").maxAge(maxAge).build();
    }
}
