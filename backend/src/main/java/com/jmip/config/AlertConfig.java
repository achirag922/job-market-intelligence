package com.jmip.config;

import com.jmip.service.alert.AlertEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** V8.4: how alert digests are delivered. Credentials only ever come from JMIP_MAIL_* variables. */
@Configuration
@EnableConfigurationProperties(AlertProperties.class)
public class AlertConfig {

    private static final Logger log = LoggerFactory.getLogger(AlertConfig.class);

    @Bean
    public AlertEmailSender alertEmailSender(AlertProperties properties, ObjectProvider<JavaMailSender> mailSender,
                                             ObjectProvider<MailProperties> mailProperties) {
        if (properties.delivery() == AlertProperties.Delivery.LOG) {
            log.info("Job alert digests are written to the log (JMIP_ALERTS_DELIVERY=log), not emailed");
            return new AlertEmailSender.Log();
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        MailProperties mail = mailProperties.getIfAvailable();
        String from = properties.mailFrom().isBlank() && mail != null ? mail.getUsername() : properties.mailFrom();
        if (sender == null || from == null || from.isBlank()) {
            throw new IllegalStateException("JMIP_ALERTS_DELIVERY=smtp needs JMIP_MAIL_HOST, JMIP_MAIL_USERNAME and "
                    + "JMIP_MAIL_PASSWORD (or JMIP_ALERTS_MAIL_FROM)");
        }
        log.info("Job alert digests are emailed through {}", mail == null ? "the configured SMTP server" : mail.getHost());
        return new AlertEmailSender.Smtp(sender, from);
    }
}
