package com.jmip.service.admin;

import com.jmip.entity.User;
import com.jmip.entity.UserRole;
import com.jmip.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * V8.9: the one way an account becomes ADMIN. At startup, every verified account whose email is
 * in {@code JMIP_ADMIN_EMAILS} is promoted. Only the operator controls that variable, and
 * verification proves the address's owner holds the mailbox, so a stranger who registers the
 * address first cannot become admin. Nothing is ever demoted here, and no email is logged.
 */
@Component
@EnableConfigurationProperties(AdminBootstrap.AdminProperties.class)
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    @ConfigurationProperties(prefix = "jmip.admin")
    public record AdminProperties(@DefaultValue List<String> bootstrapEmails) {
    }

    private final AdminProperties properties;
    private final UserRepository userRepository;
    private final Clock clock;

    public AdminBootstrap(AdminProperties properties, UserRepository userRepository, Clock clock) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** @return how many accounts were promoted */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public int promote() {
        List<String> emails = properties.bootstrapEmails().stream()
                .map(email -> email == null ? "" : email.strip().toLowerCase(Locale.ROOT))
                .filter(email -> !email.isEmpty()).distinct().toList();
        if (emails.isEmpty()) {
            return 0;
        }
        int promoted = 0;
        int waiting = 0;
        for (String email : emails) {
            Optional<User> account = userRepository.findByEmail(email);
            if (account.isEmpty() || !account.get().isEmailVerified()) {
                waiting++;
            } else if (account.get().getRole() != UserRole.ADMIN) {
                account.get().changeRole(UserRole.ADMIN, OffsetDateTime.now(clock));
                promoted++;
            }
        }
        log.info("Admin bootstrap: {} account(s) promoted to ADMIN; {} listed address(es) have no verified account yet",
                promoted, waiting);
        return promoted;
    }
}
