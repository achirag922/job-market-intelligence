package com.jmip.config;

import com.jmip.service.auth.LogVerificationCodeSender;
import com.jmip.service.auth.SmtpVerificationCodeSender;
import com.jmip.service.auth.VerificationCodeSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** Chooses how verification codes are delivered. */
@Configuration
@EnableConfigurationProperties(VerificationProperties.class)
public class VerificationConfig {

    private static final Logger log = LoggerFactory.getLogger(VerificationConfig.class);

    @Bean
    public VerificationCodeSender verificationCodeSender(VerificationProperties properties,
                                                         ObjectProvider<JavaMailSender> mailSender,
                                                         ObjectProvider<MailProperties> mailProperties) {
        if (properties.delivery() == VerificationProperties.Delivery.LOG) {
            log.warn("Verification codes are written to the log (JMIP_VERIFICATION_DELIVERY=log). "
                    + "Use smtp anywhere real people sign up.");
            return new LogVerificationCodeSender();
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("JMIP_VERIFICATION_DELIVERY=smtp needs JMIP_MAIL_HOST and credentials");
        }
        String from = properties.mailFrom().isBlank()
                ? mailProperties.getObject().getUsername()
                : properties.mailFrom();
        log.info("Verification codes are emailed through {}", mailProperties.getObject().getHost());
        return new SmtpVerificationCodeSender(sender, from);
    }
}
