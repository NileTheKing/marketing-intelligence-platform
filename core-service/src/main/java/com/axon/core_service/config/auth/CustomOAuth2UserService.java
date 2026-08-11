package com.axon.core_service.config.auth;

import com.axon.core_service.domain.user.CustomOAuth2User;

import com.axon.core_service.config.auth.dto.OAuthAttributes;
import com.axon.core_service.domain.user.User;
import com.axon.core_service.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final UserRepository userRepository;
    private final Set<String> adminEmails;

    public CustomOAuth2UserService(
            UserRepository userRepository,
            @Value("${axon.security.admin-emails:}") String configuredAdminEmails) {
        this.userRepository = userRepository;
        this.adminEmails = parseEmails(configuredAdminEmails);
    }

    /**
     * Load or create an application user from the OAuth2 provider response and return a CustomOAuth2User representing that user.
     *
     * @param userRequest the incoming OAuth2UserRequest containing client registration and access token used to fetch provider user information
     * @return an OAuth2User (specifically a CustomOAuth2User) whose authorities, attributes, and name-attribute key are set from the provider and which includes the application's user id
     * @throws OAuth2AuthenticationException if fetching or processing the provider's user information fails
     */
    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        //log.info("--- CustomOAuth2UserService.loadUser() --- START");
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = new DefaultOAuth2UserService();
        OAuth2User oAuth2User = delegate.loadUser(userRequest);

        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        String userNameAttributeName = userRequest.getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
        //log.info("Registration ID: {}, User Name Attribute Name: {}", registrationId, userNameAttributeName);
        //log.debug("OAuth2User Attributes: {}", oAuth2User.getAttributes());

        OAuthAttributes attributes = OAuthAttributes.of(registrationId, userNameAttributeName, oAuth2User.getAttributes());

        User user = saveOrUpdate(attributes);

        //log.info("User successfully loaded/saved. Email: {}", user.getEmail());
        //log.info("--- CustomOAuth2UserService.loadUser() --- END");

        return new CustomOAuth2User(
                Collections.singleton(new SimpleGrantedAuthority(user.getRoleKey())),
                attributes.getAttributes(),
                attributes.getNameAttributeKey(),
                user.getId(), // CustomOAuth2User에 우리 DB의 userId를 담아서 반환
                user.getName() // displayName
        );
    }

    User saveOrUpdate(OAuthAttributes attributes) {
        User user = userRepository.findByEmail(attributes.getEmail())
                .map(entity -> entity.update(attributes.getName(), attributes.getPicture()))
                .orElseGet(attributes::toEntity);

        if (adminEmails.contains(normalizeEmail(attributes.getEmail()))) {
            user.promoteToAdmin();
        }

        return userRepository.save(user);
    }

    private static Set<String> parseEmails(String configuredAdminEmails) {
        return Arrays.stream(configuredAdminEmails.split(","))
                .map(CustomOAuth2UserService::normalizeEmail)
                .filter(email -> !email.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
