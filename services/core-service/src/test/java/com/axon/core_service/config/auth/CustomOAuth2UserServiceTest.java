package com.axon.core_service.config.auth;

import com.axon.core_service.config.auth.dto.OAuthAttributes;
import com.axon.core_service.domain.user.Role;
import com.axon.core_service.domain.user.User;
import com.axon.core_service.repository.UserRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomOAuth2UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);

    @Test
    void configuredEmailIsPromotedForNewUser() {
        CustomOAuth2UserService service = new CustomOAuth2UserService(
                userRepository,
                "other@example.com, Admin@Example.com ");
        OAuthAttributes attributes = attributes("admin@example.com");
        when(userRepository.findByEmail(attributes.getEmail())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = service.saveOrUpdate(attributes);

        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void configuredEmailPromotesExistingUserWithoutReplacingIdentity() {
        CustomOAuth2UserService service = new CustomOAuth2UserService(userRepository, "admin@example.com");
        OAuthAttributes attributes = attributes("admin@example.com");
        User existing = User.builder()
                .name("old-name")
                .email("admin@example.com")
                .picture("old-picture")
                .role(Role.USER)
                .build();
        when(userRepository.findByEmail(attributes.getEmail())).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(existing);

        User saved = service.saveOrUpdate(attributes);

        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getName()).isEqualTo("name");
        assertThat(saved.getPicture()).isEqualTo("picture");
    }

    @Test
    void missingConfigurationKeepsDefaultUserRole() {
        CustomOAuth2UserService service = new CustomOAuth2UserService(userRepository, "");
        OAuthAttributes attributes = attributes("user@example.com");
        when(userRepository.findByEmail(attributes.getEmail())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = service.saveOrUpdate(attributes);

        assertThat(saved.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void existingAdminIsNotDemotedWhenConfigurationChanges() {
        CustomOAuth2UserService service = new CustomOAuth2UserService(userRepository, "");
        OAuthAttributes attributes = attributes("admin@example.com");
        User existing = User.builder()
                .name("name")
                .email("admin@example.com")
                .role(Role.ADMIN)
                .build();
        when(userRepository.findByEmail(attributes.getEmail())).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(existing);

        User saved = service.saveOrUpdate(attributes);

        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
    }

    private OAuthAttributes attributes(String email) {
        return OAuthAttributes.builder()
                .attributes(Map.of("id", "provider-id"))
                .nameAttributeKey("id")
                .name("name")
                .email(email)
                .picture("picture")
                .provider("naver")
                .providerId("provider-id")
                .build();
    }
}
