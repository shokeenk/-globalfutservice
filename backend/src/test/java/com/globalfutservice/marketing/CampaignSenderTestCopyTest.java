package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.notify.email.TransactionalEmails;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The message a test send actually builds, with the mail server mocked out.
 */
class CampaignSenderTestCopyTest {

    private JavaMailSender mailSender;
    private CampaignRecipientRepository recipients;
    private CampaignSender sender;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage())
                .thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        recipients = mock(CampaignRecipientRepository.class);
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.notifications().emailFrom()).thenReturn("orders@globalfutservices.com");
        when(props.notifications().emailFromName()).thenReturn("Global FUT Services");
        sender = new CampaignSender(mock(CampaignRepository.class), recipients,
                mock(AccountRepository.class), mailSender, props, mock(CampaignRenderer.class));
    }

    @Test
    @DisplayName("addresses one message to the admin, marked as a test, from the business")
    void buildsTheMessage() throws Exception {
        sender.sendTest("admin@example.test",
                new TransactionalEmails.Rendered("TOTY is here", "<html>copy</html>", "copy"));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage m = sent.getValue();
        assertThat(m.getRecipients(Message.RecipientType.TO)).extracting(Object::toString)
                .containsExactly("admin@example.test");
        assertThat(m.getSubject()).isEqualTo("[TEST] TOTY is here");
        assertThat(m.getFrom()[0].toString()).contains("orders@globalfutservices.com");
    }

    @Test
    @DisplayName("records nothing: a test is not a recipient")
    void recordsNothing() {
        sender.sendTest("admin@example.test",
                new TransactionalEmails.Rendered("s", "<html></html>", "t"));

        verifyNoInteractions(recipients);
    }

    @Test
    @DisplayName("passes the mail server's reason up to the caller")
    void surfacesTheReason() {
        doThrow(new MailSendException("535 Authentication failed")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.sendTest("admin@example.test",
                new TransactionalEmails.Rendered("s", "<html></html>", "t")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("535 Authentication failed");
    }
}
