package com.ash.springai.interview_platform.auth;

import com.ash.springai.interview_platform.Entity.AuthUserEntity;
import com.ash.springai.interview_platform.Entity.AuthRoleEntity;
import com.ash.springai.interview_platform.Entity.AuthUserRoleEntity;
import com.ash.springai.interview_platform.Repository.AuthUserRepository;
import com.ash.springai.interview_platform.Repository.AuthUserRoleRepository;
import com.ash.springai.interview_platform.Repository.AuthRolePermissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthUserServiceTests {

    @Test
    void shouldAuthenticateAgainstDatabaseUserWithEncryptedPassword() {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUserRoleRepository userRoleRepository = mock(AuthUserRoleRepository.class);
        AuthRolePermissionRepository rolePermissionRepository = mock(AuthRolePermissionRepository.class);

        AuthUserEntity entity = AuthUserEntity.of("ash", encoder.encode("123456"), true);
        when(repository.findByUsernameAndEnabledTrue("ash")).thenReturn(Optional.of(
            entity
        ));
        AuthRoleEntity role = AuthRoleEntity.of("ADMIN", "admin");
        when(userRoleRepository.findByUserId(entity.getId())).thenReturn(List.of(AuthUserRoleEntity.of(entity, role)));
        when(rolePermissionRepository.findPermissionCodesByUserId(entity.getId())).thenReturn(List.of("ADMIN:ACCESS"));

        AuthUserService service = new AuthUserService(repository, userRoleRepository, rolePermissionRepository, encoder);
        AuthUserService.AuthenticatedUser user = service.authenticate("ash", "123456");
        assertEquals("ash", user.username());
        assertEquals(List.of("ADMIN"), user.roles());
        assertEquals(List.of("ADMIN:ACCESS"), user.permissions());
    }

    @Test
    void shouldRejectWhenPasswordDoesNotMatchEncryptedValue() {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUserRoleRepository userRoleRepository = mock(AuthUserRoleRepository.class);
        AuthRolePermissionRepository rolePermissionRepository = mock(AuthRolePermissionRepository.class);

        AuthUserEntity entity = AuthUserEntity.of("ash", encoder.encode("123456"), true);
        when(repository.findByUsernameAndEnabledTrue("ash")).thenReturn(Optional.of(
            entity
        ));
        when(userRoleRepository.findByUserId(entity.getId())).thenReturn(List.of());
        when(rolePermissionRepository.findPermissionCodesByUserId(entity.getId())).thenReturn(List.of());

        AuthUserService service = new AuthUserService(repository, userRoleRepository, rolePermissionRepository, encoder);
        assertThrows(BadCredentialsException.class, () -> service.authenticate("ash", "wrong"));
    }
}

