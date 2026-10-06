package com.jmip.service.auth;

import com.jmip.entity.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Lets Spring Security load users from the {@code users} table, by email. Defining it also
 * stops Spring Boot creating its default in-memory user, whose generated password it would
 * otherwise print to the log at startup.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserService userService;

    public AppUserDetailsService(UserService userService) {
        this.userService = userService;
    }

    /** V8.9: an admin is also a user, so every signed-in API stays open to them. */
    static List<SimpleGrantedAuthority> authoritiesOf(com.jmip.entity.UserRole role) {
        if (role == com.jmip.entity.UserRole.ADMIN) {
            return List.of(new SimpleGrantedAuthority(com.jmip.entity.UserRole.ADMIN.authority()),
                    new SimpleGrantedAuthority(com.jmip.entity.UserRole.USER.authority()));
        }
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        User user = userService.findByEmail(email)
                // Same message whether or not the account exists, so it cannot be used to probe.
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(authoritiesOf(user.getRole()))
                .build();
    }
}
