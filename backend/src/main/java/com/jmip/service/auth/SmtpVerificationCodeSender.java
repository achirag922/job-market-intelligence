package com.jmip.service.auth;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

/**
 * Sends the code by email through the configured SMTP server — for Gmail, smtp.gmail.com with
 * an app password (a normal account password is refused). Plain text: nothing to render,
 * nothing to track.
 */
public class SmtpVerificationCodeSender implements VerificationCodeSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpVerificationCodeSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String email, String fullName, String code, Duration validFor) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Your JMIP verification code: " + code);
        message.setText("""
                Hello%s,

                Your Job Market Intelligence verification code is:

                    %s

                It expires in %d minutes. If you did not create an account, you can ignore this email.
                """.formatted(fullName == null || fullName.isBlank() ? "" : " " + fullName, code, validFor.toMinutes()));
        mailSender.send(message);
    }
}
