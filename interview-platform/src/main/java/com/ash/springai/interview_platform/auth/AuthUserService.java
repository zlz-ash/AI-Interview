package com.ash.springai.interview_platform.auth;

import com.ash.springai.interview_platform.Entity.AuthUserEntity;
import com.ash.springai.interview_platform.Entity.AuthUserRoleEntity;
import com.ash.springai.interview_platform.Repository.AuthUserRepository;
import com.ash.springai.interview_platform.Repository.AuthUserRoleRepository;
import com.ash.springai.interview_platform.Repository.AuthRolePermissionRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthUserService implements UserDetailsService {

    private final AuthUserRepository authUserRepository;
    private final AuthUserRoleRepository authUserRoleRepository;
    private final AuthRolePermissionRepository authRolePermissionRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthUserService(
        AuthUserRepository authUserRepository,
        AuthUserRoleRepository authUserRoleRepository,
        AuthRolePermissionRepository authRolePermissionRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.authUserRepository = authUserRepository;
        this.authUserRoleRepository = authUserRoleRepository;
        this.authRolePermissionRepository = authRolePermissionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AuthUserEntity userEntity = authUserRepository.findByUsernameAndEnabledTrue(username)
            .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));
        List<String> roles = resolveRoles(userEntity.getId());
        List<String> permissions = resolvePermissions(userEntity.getId());

        List<GrantedAuthority> roleAuthorities = roles.stream()
            .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
            .toList();
        List<GrantedAuthority> permissionAuthorities = permissions.stream()
            .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p))
            .toList();

        java.util.List<GrantedAuthority> authorities = new java.util.ArrayList<>(roleAuthorities);
        authorities.addAll(permissionAuthorities);

        return User.withUsername(userEntity.getUsername())
            .password(userEntity.getPasswordHash())
            .authorities(authorities)
            .build();
    }

    public AuthenticatedUser authenticate(String username, String rawPassword) {
        AuthUserEntity userEntity = authUserRepository.findByUsernameAndEnabledTrue(username)
            .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));
        boolean matches = passwordEncoder.matches(rawPassword, userEntity.getPasswordHash());

        if (!matches) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        List<String> roles = resolveRoles(userEntity.getId());
        List<String> permissions = resolvePermissions(userEntity.getId());
        return new AuthenticatedUser(userEntity.getId(), userEntity.getUsername(), roles, permissions);
    }

    public AuthenticatedUser loadAuthenticatedUser(String username) {
        AuthUserEntity userEntity = authUserRepository.findByUsernameAndEnabledTrue(username)
            .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));
        List<String> roles = resolveRoles(userEntity.getId());
        List<String> permissions = resolvePermissions(userEntity.getId());
        return new AuthenticatedUser(userEntity.getId(), userEntity.getUsername(), roles, permissions);
    }

    private List<String> resolveRoles(Long userId) {
        return authUserRoleRepository.findByUserId(userId).stream()
            .map(AuthUserRoleEntity::getRole)
            .filter(role -> role != null && role.isEnabled())
            .map(role -> role.getName())
            .distinct()
            .toList();
    }

    private List<String> resolvePermissions(Long userId) {
        return authRolePermissionRepository.findPermissionCodesByUserId(userId).stream()
            .distinct()
            .toList();
    }

    public record AuthenticatedUser(Long userId, String username, List<String> roles, List<String> permissions) {}
}

