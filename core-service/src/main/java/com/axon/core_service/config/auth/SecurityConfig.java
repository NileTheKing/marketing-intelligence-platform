package com.axon.core_service.config.auth;

import com.axon.core_service.domain.user.Role;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

        private final JwtTokenProvider jwtTokenProvider;
        private final CustomOAuth2UserService customOAuth2UserService;
        private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
        private final CustomLogoutHandler customLogoutHandler;

        /**
         * Creates a JwtAuthenticationFilter initialized with the configured
         * JwtTokenProvider.
         *
         * @return a JwtAuthenticationFilter that validates JWTs using the application's
         *         JwtTokenProvider
         */
        @Bean
        public JwtAuthenticationFilter jwtAuthenticationFilter() {
                return new JwtAuthenticationFilter(jwtTokenProvider);
        }

        /**
         * Configure and build the application's HTTP security chain, including session,
         * CSRF,
         * authorization rules, OAuth2 login, exception handling, logout, and JWT filter
         * placement.
         *
         * @param jwtAuthenticationFilter the JWT authentication filter to add after
         *                                OAuth2 login processing
         * @return the configured SecurityFilterChain
         */
        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter)
                        throws Exception {
                http
                                .httpBasic(httpBasic -> httpBasic.disable())
                                .csrf(csrf -> csrf.disable())
                                .sessionManagement(sm -> sm
                                                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))  // Allow session for OAuth2 login to preserve original request
                                .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.disable()))
                                .authorizeHttpRequests(authz -> authz
                                                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                                                .requestMatchers("/", "/shop", "/css/**", "/image/**", "/images/**", "/js/**", "/uploads/**",
                                                                "/favicon.ico", "/welcomepage", "/welcomepage.html",
                                                                "/oauth2/**", "/login/**", "/test/**")
                                                .permitAll()
                                                .requestMatchers(HttpMethod.GET, "/api/v1/events/active")
                                                .permitAll()
                                                .requestMatchers(HttpMethod.GET, "/api/v1/campaign-activities/{campaignActivityId:[0-9]+}")
                                                .hasAnyAuthority(Role.ADMIN.getKey(), "ROLE_SYSTEM")
                                                .requestMatchers("/admin/**", "/api/v1/campaigns/**",
                                                                "/api/v1/campaign-activities/**", "/api/v1/coupons/**",
                                                                "/api/v1/dashboard/**", "/api/v1/events/**",
                                                                "/api/v1/files/**", "/api/v1/monitoring/**",
                                                                "/api/v1/products/**", "/core/api/v1/**",
                                                                "/fake/data/**")
                                                .hasAuthority(Role.ADMIN.getKey())
                                                .requestMatchers("/api/v1/validation")
                                                .authenticated()
                                                .requestMatchers("/api/v1/**").authenticated()
                                                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                                                .anyRequest().authenticated())
                                .exceptionHandling(exceptions -> exceptions
                                                .authenticationEntryPoint((request, response, authException) -> {
                                                        String uri = request.getRequestURI();
                                                        if (uri.startsWith("/api/") || uri.startsWith("/core/api/")) {
                                                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
                                                                        .commence(request, response, authException);
                                                                return;
                                                        }
                                                        new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/naver")
                                                                .commence(request, response, authException);
                                                }))
                                .logout(logout -> logout
                                                .logoutUrl("/logout")
                                                .logoutSuccessUrl("/shop")
                                                .deleteCookies("accessToken", "refreshToken")  // Delete JWT cookies
                                                .addLogoutHandler(customLogoutHandler)  // Custom cleanup (Redis, events)
                                                .permitAll())
                                .oauth2Login(oauth2 -> oauth2
                                                .loginPage("/oauth2/authorization/naver")
                                                .userInfoEndpoint(userInfo -> userInfo
                                                                .userService(customOAuth2UserService))
                                                .successHandler(oAuth2AuthenticationSuccessHandler)
                                                .failureHandler(new SimpleUrlAuthenticationFailureHandler("/")))
                                .addFilterAfter(jwtAuthenticationFilter, OAuth2LoginAuthenticationFilter.class);
                return http.build();
        }
}
