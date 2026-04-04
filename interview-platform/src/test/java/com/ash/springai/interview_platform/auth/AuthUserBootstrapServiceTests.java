package com.ash.springai.interview_platform.auth;

import com.ash.springai.interview_platform.Entity.AuthUserEntity;
import com.ash.springai.interview_platform.Entity.AuthPermissionEntity;
import com.ash.springai.interview_platform.Entity.AuthRoleEntity;
import com.ash.springai.interview_platform.Repository.AuthUserRepository;
import com.ash.springai.interview_platform.Repository.AuthPermissionRepository;
import com.ash.springai.interview_platform.Repository.AuthRolePermissionRepository;
import com.ash.springai.interview_platform.Repository.AuthRoleRepository;
import com.ash.springai.interview_platform.Repository.AuthUserRoleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthUserBootstrapServiceTests {

    @Test
    void shouldPersistSeedUserWithEncryptedPassword() {
        AuthProperties properties = new AuthProperties();
        AuthProperties.UserConfig seed = new AuthProperties.UserConfig();
        seed.setUsername("admin");
        seed.setPassword("admin123");
        seed.setRoles(Collections.singletonList("ADMIN"));
        properties.setUsers(Collections.singletonList(seed));

        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthRoleRepository roleRepository = mock(AuthRoleRepository.class);
        AuthPermissionRepository permissionRepository = mock(AuthPermissionRepository.class);
        AuthUserRoleRepository userRoleRepository = mock(AuthUserRoleRepository.class);
        AuthRolePermissionRepository rolePermissionRepository = mock(AuthRolePermissionRepository.class);

        when(permissionRepository.findByCode(any())).thenReturn(Optional.empty());
        when(permissionRepository.save(any(AuthPermissionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(roleRepository.findByName(any())).thenReturn(Optional.empty());
        when(roleRepository.save(any(AuthRoleEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(rolePermissionRepository.findByRoleAndPermission(any(), any())).thenReturn(Optional.empty());
        when(userRoleRepository.findByUserAndRole(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any(AuthUserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByUsername("admin")).thenReturn(Optional.empty());

        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        AuthUserBootstrapService bootstrapService = new AuthUserBootstrapService(
            properties,
            repository,
            roleRepository,
            permissionRepository,
            userRoleRepository,
            rolePermissionRepository,
            encoder
        );
        bootstrapService.bootstrap();

        ArgumentCaptor<AuthUserEntity> captor = ArgumentCaptor.forClass(AuthUserEntity.class);
        verify(repository).save(captor.capture());
        AuthUserEntity saved = captor.getValue();
        assertNotEquals("admin123", saved.getPasswordHash());
        assertTrue(saved.getPasswordHash().startsWith("{bcrypt}"));
    }
}

