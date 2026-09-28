package com.jmip.service.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** V8.4: delivers one alert digest. Throws when it could not be delivered. */
public interface AlertEmailSender {

    void send(String to, AlertDigest digest);

    /** Development and tests: nothing leaves the server, and the recipient is not logged. */
    final class Log implements AlertEmailSender {

        private static final Logger log = LoggerFactory.getLogger(AlertEmailSender.class);

        @Override
        public void send(String to, AlertDigest digest) {
            log.info("Alert digest not emailed (JMIP_ALERTS_DELIVERY=log): \"{}\"", digest.subject());
            log.debug("Alert digest body:\n{}", digest.body());
        }
    }

    /** Plain text through the configured SMTP server, like the verification codes. */
    final class Smtp implements AlertEmailSender {

        private final JavaMailSender mailSender;
        private final String from;

        public Smtp(JavaMailSender mailSender, String from) {
            this.mailSender = mailSender;
            this.from = from;
        }

        @Override
        public void send(String to, AlertDigest digest) {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(digest.subject());
            message.setText(digest.body());
            mailSender.send(message);
        }
    }
}
