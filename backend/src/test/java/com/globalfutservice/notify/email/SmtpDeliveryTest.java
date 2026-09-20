package com.globalfutservice.notify.email;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.marketing.CampaignAudience;
import com.globalfutservice.marketing.CampaignEntity;
import com.globalfutservice.marketing.CampaignRecipientEntity;
import com.globalfutservice.marketing.CampaignRecipientRepository;
import com.globalfutservice.marketing.CampaignRenderer;
import com.globalfutservice.marketing.CampaignRepository;
import com.globalfutservice.marketing.CampaignSender;
import com.globalfutservice.notify.EmailNotifier;
import com.globalfutservice.notify.OrderNotification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mail actually leaving the application, over a real SMTP conversation.
 *
 * <p>Every other email test in this package asserts on a rendered string. That proves the
 * template is right and proves nothing about delivery: the transport is configured
 * separately, {@code MimeMessageHelper} can reject content the renderer was happy with,
 * and headers set on the {@code MimeMessage} rather than through the helper are exactly
 * the kind that go missing without anybody noticing. So this one speaks SMTP and asserts
 * on the bytes that went down the socket.
 *
 * <p>The server below is a few lines of protocol and lives in this file on purpose: the
 * check then runs anywhere, with no Docker, no Mailpit and no credentials.
 *
 * <p><b>What this does not prove.</b> That a real provider accepts the message, that SPF
 * or DKIM pass, or that anything lands in an inbox. Those need production SMTP
 * credentials, which this repository does not have.
 */
class SmtpDeliveryTest {

    private static final String SITE = "https://globalfutservices.com";

    private SmtpSink sink;
    private JavaMailSenderImpl mailSender;
    private AppProperties props;

    /** Accepts SMTP sessions and keeps the DATA payload of each message. */
    private static final class SmtpSink implements AutoCloseable {

        private final ServerSocket socket;
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch arrivals = new CountDownLatch(1);

        SmtpSink() throws IOException {
            socket = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::acceptLoop, "smtp-sink");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        void expect(int howMany) {
            arrivals = new CountDownLatch(howMany);
        }

        /** @return true if the expected messages arrived inside the timeout */
        boolean awaitArrivals() throws InterruptedException {
            return arrivals.await(20, TimeUnit.SECONDS);
        }

        /** @return true if nothing arrived, having genuinely waited for it */
        boolean awaitSilence() throws InterruptedException {
            return !arrivals.await(2, TimeUnit.SECONDS);
        }

        List<String> messages() {
            return messages;
        }

        private void acceptLoop() {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    Thread session = new Thread(() -> converse(client), "smtp-sink-session");
                    session.setDaemon(true);
                    session.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void converse(Socket client) {
            try (client;
                 BufferedReader in = new BufferedReader(new InputStreamReader(
                         client.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream out = client.getOutputStream()) {

                say(out, "220 sink.test ESMTP");
                StringBuilder body = null;
                String line;
                while ((line = in.readLine()) != null) {
                    if (body != null) {
                        if (".".equals(line)) {
                            messages.add(body.toString());
                            body = null;
                            arrivals.countDown();
                            say(out, "250 2.0.0 Queued");
                        } else {
                            // Undo dot-stuffing, or an assertion reads content that the
                            // recipient would never have seen.
                            body.append(line.startsWith("..") ? line.substring(1) : line)
                                    .append('\n');
                        }
                        continue;
                    }
                    String upper = line.toUpperCase(Locale.ROOT);
                    if (upper.startsWith("EHLO")) {
                        say(out, "250-sink.test");
                        say(out, "250 8BITMIME");
                    } else if (upper.startsWith("DATA")) {
                        say(out, "354 Go ahead");
                        body = new StringBuilder();
                    } else if (upper.startsWith("QUIT")) {
                        say(out, "221 Bye");
                        return;
                    } else {
                        say(out, "250 OK");
                    }
                }
            } catch (IOException ignored) {
                // A client hanging up mid-session is not this test's concern.
            }
        }

        private static void say(OutputStream out, String s) throws IOException {
            out.write((s + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        sink = new SmtpSink();

        mailSender = new JavaMailSenderImpl();
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(sink.port());
        Properties mailProps = new Properties();
        mailProps.put("mail.smtp.auth", "false");
        mailProps.put("mail.smtp.starttls.enable", "false");
        mailProps.put("mail.smtp.timeout", "10000");
        mailProps.put("mail.smtp.connectiontimeout", "10000");
        mailSender.setJavaMailProperties(mailProps);

        props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.publicUrl()).thenReturn(SITE);
        when(props.discordInvite()).thenReturn("https://discord.com/invite/8FeP7C6tXt");
        when(props.instagramUrl()).thenReturn("https://www.instagram.com/global_fut_services/");
        when(props.notifications().emailEnabled()).thenReturn(true);
        when(props.notifications().emailFrom()).thenReturn("orders@globalfutservices.com");
        when(props.notifications().emailFromName()).thenReturn("Global Fut Services");
    }

    @AfterEach
    void tearDown() throws IOException {
        sink.close();
    }

    private static OrderNotification coinsOrder(String status) {
        return new OrderNotification(
                "GFS-1042", status, "EA FC Coins 100K", "INR 1,600.00",
                "player@example.com", "player#1234", "COMFORT_TRADE",
                "TRADING_SERVICE", "PS5", null, null);
    }

    private static OrderNotification coachingOrder() {
        return new OrderNotification(
                "GFS-2088", "CONFIRMED", "1-to-1 Coaching", "INR 2,400.00",
                "student@example.com", "student#9999", null,
                "COACHING", "PC", null, "PC / student99 / Div 4 / finishing");
    }

    @Nested
    @DisplayName("the two order emails")
    class Transactional {

        @Test
        @DisplayName("awaiting verification is accepted by an SMTP server, intact")
        void awaitingVerificationIsDelivered() throws Exception {
            sink.expect(1);
            new EmailNotifier(props, mailSender)
                    .awaitingVerification(coinsOrder("AWAITING_VERIFICATION"));

            assertThat(sink.awaitArrivals()).as("a message arrived").isTrue();
            String raw = sink.messages().get(0);

            assertThat(raw).contains("To: player@example.com");
            assertThat(raw).contains("multipart/alternative");
            assertThat(decodedBody(raw)).contains("GFS-1042");
        }

        @Test
        @DisplayName("a coins confirmation is delivered with its order reference")
        void coinsConfirmationIsDelivered() throws Exception {
            sink.expect(1);
            new EmailNotifier(props, mailSender).orderConfirmed(coinsOrder("CONFIRMED"));

            assertThat(sink.awaitArrivals()).isTrue();
            assertThat(decodedBody(sink.messages().get(0))).contains("GFS-1042");
        }

        @Test
        @DisplayName("a coaching confirmation is delivered too, on the other branch")
        void coachingConfirmationIsDelivered() throws Exception {
            sink.expect(1);
            new EmailNotifier(props, mailSender).orderConfirmed(coachingOrder());

            assertThat(sink.awaitArrivals()).isTrue();
            assertThat(sink.messages().get(0)).contains("To: student@example.com");
        }

        @Test
        @DisplayName("order mail carries no unsubscribe header, because it is not marketing")
        void transactionalIsNotUnsubscribable() throws Exception {
            sink.expect(1);
            new EmailNotifier(props, mailSender).orderConfirmed(coinsOrder("CONFIRMED"));

            assertThat(sink.awaitArrivals()).isTrue();
            assertThat(sink.messages().get(0)).doesNotContain("List-Unsubscribe");
        }

        @Test
        @DisplayName("nothing is sent at all when email is switched off")
        void disabledSendsNothing() throws Exception {
            when(props.notifications().emailEnabled()).thenReturn(false);
            sink.expect(1);

            new EmailNotifier(props, mailSender).orderConfirmed(coinsOrder("CONFIRMED"));

            assertThat(sink.awaitSilence()).isTrue();
        }
    }

    @Nested
    @DisplayName("a campaign send")
    class Promotional {

        private CampaignSender sender;
        private CampaignRecipientEntity row;
        private AccountEntity account;

        @BeforeEach
        void wireCampaign() {
            CampaignRepository campaigns = mock(CampaignRepository.class);
            CampaignRecipientRepository recipients = mock(CampaignRecipientRepository.class);
            AccountRepository accounts = mock(AccountRepository.class);

            CampaignEntity campaign = new CampaignEntity(
                    "Friday Coin Sale", "Friday Coin Sale - 10% off", "FRIDAY COIN SALE",
                    "Every coin order is 10% off for 48 hours.",
                    CampaignAudience.ALL_OPTED_IN, 1L);
            campaign.setCtaText("BUY COINS");
            campaign.setCtaPath("/order?service=TRADING_SERVICE");
            campaign.setPromoCode("FRIDAY10");

            account = new AccountEntity("acc_1", "player@example.com", "hash",
                    AccountRole.CUSTOMER);
            account.optInToMarketing();

            row = new CampaignRecipientEntity(7L, 3L, "player@example.com");

            when(campaigns.findById(7L)).thenReturn(Optional.of(campaign));
            when(recipients.findById(anyLong())).thenReturn(Optional.of(row));
            when(accounts.findById(3L)).thenReturn(Optional.of(account));

            sender = new CampaignSender(campaigns, recipients, accounts, mailSender, props,
                    new CampaignRenderer(props));
        }

        @Test
        @DisplayName("reaches the SMTP server and is marked sent")
        void campaignIsDelivered() throws Exception {
            sink.expect(1);

            assertThat(sender.sendOne(7L, 11L)).isTrue();

            assertThat(sink.awaitArrivals()).isTrue();
            assertThat(row.getStatus()).isEqualTo("SENT");
            assertThat(sink.messages().get(0)).contains("To: player@example.com");
        }

        @Test
        @DisplayName("carries the one-click unsubscribe headers a mail client needs")
        void unsubscribeHeadersSurviveTheTransport() throws Exception {
            sink.expect(1);
            sender.sendOne(7L, 11L);
            assertThat(sink.awaitArrivals()).isTrue();

            String raw = decodedBody(sink.messages().get(0));
            assertThat(raw).contains("List-Unsubscribe:");
            assertThat(raw).contains(account.getMarketingToken().toString());
            assertThat(raw).contains("List-Unsubscribe-Post: List-Unsubscribe=One-Click");
        }

        @Test
        @DisplayName("its button goes through the click counter")
        void ctaIsCounted() throws Exception {
            sink.expect(1);
            sender.sendOne(7L, 11L);
            assertThat(sink.awaitArrivals()).isTrue();

            assertThat(decodedBody(sink.messages().get(0)))
                    .contains("/api/v1/marketing/c/" + row.getToken());
        }

        @Test
        @DisplayName("somebody who opted out in the meantime is skipped, not mailed")
        void optedOutMidSendIsSkipped() throws Exception {
            account.optOutOfMarketing(null);
            sink.expect(1);

            assertThat(sender.sendOne(7L, 11L)).isFalse();

            assertThat(sink.awaitSilence()).as("nothing should arrive").isTrue();
            assertThat(sink.messages()).isEmpty();
            assertThat(row.getStatus()).isEqualTo("SKIPPED");
        }
    }

    /**
     * The message with quoted-printable undone.
     *
     * <p>Needed because a URL long enough to be soft-wrapped is split across lines with a
     * trailing {@code =}, so a plain {@code contains} fails on a link that is perfectly
     * correct.
     */
    private static String decodedBody(String raw) {
        String unfolded = raw.replace("=\r\n", "").replace("=\n", "");
        StringBuilder out = new StringBuilder(unfolded.length());
        for (int i = 0; i < unfolded.length(); i++) {
            char c = unfolded.charAt(i);
            if (c == '=' && i + 2 < unfolded.length()
                    && isHex(unfolded.charAt(i + 1)) && isHex(unfolded.charAt(i + 2))) {
                out.append((char) Integer.parseInt(unfolded.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
