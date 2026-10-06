package com.globalfutservice.notify;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Arrays;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An email as {@link EmailNotifier} hands it to the mail server: subject, recipients, and the
 * HTML and plain-text parts read back out of the MIME tree.
 */
public record SentEmail(String subject, String to, String html, String text) {

    /** A mail sender whose messages are real, so what was built can be read back. */
    public static JavaMailSender sender() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage((jakarta.mail.Session) null));
        return sender;
    }

    /** The one branded email the sender was given. */
    public static SentEmail captured(JavaMailSender sender) {
        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(message.capture());
        return of(message.getValue());
    }

    public static SentEmail of(MimeMessage message) {
        try {
            // Fills in each part's Content-Type from its content, as sending would; until
            // then an HTML part built in memory reports itself as text/plain.
            message.saveChanges();
            StringBuilder html = new StringBuilder();
            StringBuilder text = new StringBuilder();
            collect(message, html, text);
            String to = String.join(",", Arrays.stream(message.getAllRecipients()).map(Object::toString).toList());
            return new SentEmail(message.getSubject(), to, html.toString(), text.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void collect(Part part, StringBuilder html, StringBuilder text) throws Exception {
        if (part.isMimeType("text/html")) {
            html.append(part.getContent());
        } else if (part.isMimeType("text/plain")) {
            text.append(part.getContent());
        } else if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i), html, text);
            }
        }
    }
}
