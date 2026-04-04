package com.ash.springai.interview_platform.auth;

import com.ash.springai.interview_platform.Entity.AuthUserEntity;
import com.ash.springai.interview_platform.Entity.AuthPermissionEntity;
import com.ash.springai.interview_platform.Entity.AuthRoleEntity;
import com.ash.springai.interview_platform.Entity.AuthRolePermissionEntity;
import com.ash.springai.interview_platform.Entity.AuthUserRoleEntity;
import com.ash.springai.interview_platform.Repository.AuthPermissionRepository;
import com.ash.springai.interview_platform.Repository.AuthUserRepository;
import com.ash.springai.interview_platform.Repository.AuthRolePermissionRepository;
import com.ash.springai.interview_platform.Repository.AuthRoleRepository;
import com.ash.springai.interview_platform.Repository.AuthUserRoleRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;

@Component
public class AuthUserBootstrapService {

    private final AuthProperties authProperties;
    private final AuthUserRepository authUserRepository;
    private final AuthRoleRepository authRoleRepository;
    private final AuthPermissionRepository authPermissionRepository;
    private final AuthUserRoleRepository authUserRoleRepository;
    private final AuthRolePermissionRepository authRolePermissionRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthUserBootstrapService(
        AuthProperties authProperties,
        AuthUserRepository authUserRepository,
        AuthRoleRepository authRoleRepository,
        AuthPermissionRepository authPermissionRepository,
        AuthUserRoleRepository authUserRoleRepository,
        AuthRolePermissionRepository authRolePermissionRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.authProperties = authProperties;
        this.authUserRepository = authUserRepository;
        this.authRoleRepository = authRoleRepository;
        this.authPermissionRepository = authPermissionRepository;
        this.authUserRoleRepository = authUserRoleRepository;
        this.authRolePermissionRepository = authRolePermissionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void bootstrap() {
        seedPermissionsAndRoles();

        Set<String> usernames = new HashSet<>();
        for (AuthProperties.UserConfig user : authProperties.getUsers()) {
            if (!StringUtils.hasText(user.getUsername()) || !StringUtils.hasText(user.getPassword())) {
                continue;
            }
            if (!usernames.add(user.getUsername())) {
                throw new IllegalStateException("检测到重复用户名配置: " + user.getUsername());
            }
            AuthUserEntity entity = authUserRepository.findByUsername(user.getUsername()).orElse(null);
            if (entity == null) {
                String encoded = passwordEncoder.encode(user.getPassword());
                entity = AuthUserEntity.of(user.getUsername(), encoded, true);
                entity = authUserRepository.save(entity);
            }
            bindRoles(entity, user.getRoles());
        }
    }

    private void seedPermissionsAndRoles() {
        AuthPermissionEntity userRead = ensurePermission("USER:READ", "user", "read", "读取用户信息");
        AuthPermissionEntity sessionAccess = ensurePermission("SESSION:ACCESS", "session", "access", "访问会话能力");
        AuthPermissionEntity interviewAccess = ensurePermission("INTERVIEW:ACCESS", "interview", "access", "访问面试模块");
        AuthPermissionEntity ragAccess = ensurePermission("RAG:ACCESS", "rag", "access", "访问RAG模块");
        AuthPermissionEntity adminAccess = ensurePermission("ADMIN:ACCESS", "admin", "access", "访问管理能力");

        AuthRoleEntity userRole = ensureRole("USER", "普通用户");
        AuthRoleEntity adminRole = ensureRole("ADMIN", "管理员");

        ensureRolePermission(userRole, userRead);
        ensureRolePermission(userRole, sessionAccess);
        ensureRolePermission(userRole, interviewAccess);
        ensureRolePermission(userRole, ragAccess);

        ensureRolePermission(adminRole, userRead);
        ensureRolePermission(adminRole, sessionAccess);
        ensureRolePermission(adminRole, interviewAccess);
        ensureRolePermission(adminRole, ragAccess);
        ensureRolePermission(adminRole, adminAccess);
    }

    private AuthPermissionEntity ensurePermission(String code, String domain, String action, String description) {
        return authPermissionRepository.findByCode(code)
            .orElseGet(() -> authPermissionRepository.save(AuthPermissionEntity.of(code, domain, action, description)));
    }

    private AuthRoleEntity ensureRole(String name, String description) {
        return authRoleRepository.findByName(name)
            .orElseGet(() -> authRoleRepository.save(AuthRoleEntity.of(name, description)));
    }

    private void ensureRolePermission(AuthRoleEntity role, AuthPermissionEntity permission) {
        if (authRolePermissionRepository.findByRoleAndPermission(role, permission).isPresent()) {
            return;
        }
        authRolePermissionRepository.save(AuthRolePermissionEntity.of(role, permission));
    }

    private void bindRoles(AuthUserEntity user, java.util.List<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return;
        }
        for (String roleName : roleNames) {
            if (!StringUtils.hasText(roleName)) {
                continue;
            }
            AuthRoleEntity role = authRoleRepository.findByName(roleName)
                .orElseGet(() -> authRoleRepository.save(AuthRoleEntity.of(roleName, "自定义角色")));
            if (authUserRoleRepository.findByUserAndRole(user, role).isPresent()) {
                continue;
            }
            authUserRoleRepository.save(AuthUserRoleEntity.of(user, role));
        }
    }
}

