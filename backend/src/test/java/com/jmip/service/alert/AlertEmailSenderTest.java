package com.jmip.service.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** V10.9: alert and follow-up digests go out through SMTP, or stay on the server with log delivery. */
@ExtendWith(OutputCaptureExtension.class)
class AlertEmailSenderTest {

    private static final AlertDigest DIGEST = new AlertDigest("3 new jobs for Backend roles", "Hello Alex,\n\n- Backend Engineer at Acme");

    @Test
    @DisplayName("SMTP delivery sends one plain-text message from the configured sender")
    void smtpSendsMessage() {
        JavaMailSender mail = mock(JavaMailSender.class);
        new AlertEmailSender.Smtp(mail, "alerts@jmip.example").send("alex@example.test", DIGEST);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("alerts@jmip.example");
        assertThat(sent.getValue().getTo()).containsExactly("alex@example.test");
        assertThat(sent.getValue().getSubject()).isEqualTo(DIGEST.subject());
        assertThat(sent.getValue().getText()).isEqualTo(DIGEST.body());
    }

    @Test
    @DisplayName("an SMTP failure propagates, so the caller can retry the digest later")
    void smtpFailurePropagates() {
        JavaMailSender mail = mock(JavaMailSender.class);
        doThrow(new MailSendException("connection refused")).when(mail).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> new AlertEmailSender.Smtp(mail, "alerts@jmip.example").send("alex@example.test", DIGEST))
                .isInstanceOf(MailSendException.class);
    }

    @Test
    @DisplayName("log delivery sends nothing and never writes the recipient's address")
    void logDeliveryKeepsRecipientPrivate(CapturedOutput output) {
        new AlertEmailSender.Log().send("alex@example.test", DIGEST);

        assertThat(output.getAll()).contains(DIGEST.subject()).doesNotContain("alex@example.test");
    }
}
