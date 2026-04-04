package com.ash.springai.interview_platform.auth;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthUserService implements UserDetailsService {

    private final Map<String, AuthProperties.UserConfig> usersByUsername;
    private final PasswordEncoder passwordEncoder;

    public AuthUserService(AuthProperties authProperties, PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.usersByUsername = new LinkedHashMap<>();
        for (AuthProperties.UserConfig user : authProperties.getUsers()) {
            if (!StringUtils.hasText(user.getUsername())) {
                continue;
            }
            AuthProperties.UserConfig previous = usersByUsername.putIfAbsent(user.getUsername(), user);
            if (previous != null) {
                throw new IllegalStateException("检测到重复用户名配置: " + user.getUsername());
            }
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AuthProperties.UserConfig userConfig = usersByUsername.get(username);
        if (userConfig == null) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        List<GrantedAuthority> authorities = userConfig.getRoles().stream()
            .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
            .toList();

        return User.withUsername(userConfig.getUsername())
            // JWT 模式下不走 Spring 登录表单流程，这里只承载用户与角色信息
            .password(userConfig.getPassword() == null ? "" : userConfig.getPassword())
            .authorities(authorities)
            .build();
    }

    public AuthenticatedUser authenticate(String username, String rawPassword) {
        AuthProperties.UserConfig userConfig = usersByUsername.get(username);
        if (userConfig == null || !StringUtils.hasText(userConfig.getPassword())) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        String storedPassword = userConfig.getPassword().startsWith("{")
            ? userConfig.getPassword()
            : "{noop}" + userConfig.getPassword();
        boolean matches = passwordEncoder.matches(rawPassword, storedPassword);

        if (!matches) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        return new AuthenticatedUser(userConfig.getUsername(), userConfig.getRoles());
    }

    public record AuthenticatedUser(String username, List<String> roles) {}
}

