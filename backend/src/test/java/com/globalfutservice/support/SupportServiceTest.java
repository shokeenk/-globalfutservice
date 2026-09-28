package com.globalfutservice.support;

import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.SupportReplyNotification;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.notify.feed.NotificationKind;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A ticket's status follows whoever wrote last; notes change nothing and go nowhere; the
 * customer's link is signed per ticket.
 */
class SupportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T06:00:00Z");

    private SupportTicketRepository tickets;
    private SupportMessageRepository messages;
    private NotificationService notifications;
    private CustomerFeedService feed;
    private SupportService service;
    private SupportTicketEntity ticket;

    @BeforeEach
    void setUp() {
        tickets = mock(SupportTicketRepository.class);
        messages = mock(SupportMessageRepository.class);
        notifications = mock(NotificationService.class);
        feed = mock(CustomerFeedService.class);
        AppProperties props = mock(AppProperties.class);
        AppProperties.Security security = mock(AppProperties.Security.class);
        when(props.security()).thenReturn(security);
        when(security.quoteSigningSecret()).thenReturn("test-only-quote-secret-00000000000000000000000000000");
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(messages.save(any())).thenAnswer(call -> call.getArgument(0));
        when(tickets.save(any())).thenAnswer(call -> {
            SupportTicketEntity t = call.getArgument(0);
            if (t.getId() == null) ReflectionTestUtils.setField(t, "id", 5L);
            return t;
        });
        service = new SupportService(tickets, messages, notifications, feed, AfterCommit.immediate(), props,
                Clock.fixed(NOW, ZoneOffset.UTC));

        ticket = new SupportTicketEntity("TKT-AB12CD34", 9L, "GFS-26-CN43SP05", "rahul07@example.test",
                "Coins not received", "Hi, I paid but have no coins.");
        ReflectionTestUtils.setField(ticket, "id", 5L);
    }

    @Test
    @DisplayName("a staff reply waits for the customer, and reaches them by email and bell")
    void reply() {
        service.staffWrite(ticket, "Your coins are on the way.", false, 2L, "vinay@example.test");

        assertThat(ticket.getStatus()).isEqualTo(SupportService.ANSWERED);
        assertThat(ticket.getLastActivityAt()).isEqualTo(NOW);
        ArgumentCaptor<SupportReplyNotification> sent = ArgumentCaptor.forClass(SupportReplyNotification.class);
        verify(notifications).supportReply(sent.capture());
        assertThat(sent.getValue().email()).isEqualTo("rahul07@example.test");
        assertThat(sent.getValue().message()).isEqualTo("Your coins are on the way.");
        assertThat(sent.getValue().link()).startsWith("https://globalfutservices.com/support/tickets/TKT-AB12CD34?key=");
        verify(feed).record(eq(9L), eq(NotificationKind.SUPPORT_REPLY), eq("Support replied"), anyString(),
                eq("/support/tickets/TKT-AB12CD34"), isNull());
    }

    @Test
    @DisplayName("a note changes nothing and tells nobody")
    void note() {
        SupportMessageEntity saved = service.staffWrite(ticket, "Checked the partner dashboard.", true, 2L, "vinay@example.test");

        assertThat(saved.getKind()).isEqualTo(SupportMessageEntity.NOTE);
        assertThat(ticket.getStatus()).isEqualTo(SupportService.OPEN);
        verify(notifications, never()).supportReply(any());
        verify(feed, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the customer answering puts it back with staff, and reopens a resolved ticket")
    void customerReopens() {
        service.close(ticket);
        assertThat(ticket.getStatus()).isEqualTo(SupportService.CLOSED);
        assertThat(ticket.getResolvedAt()).isEqualTo(NOW);

        service.customerWrite(ticket, "Still nothing, sorry.");
        assertThat(ticket.getStatus()).isEqualTo(SupportService.OPEN);
        assertThat(ticket.getResolvedAt()).isNull();
        verify(notifications, never()).supportReply(any());
    }

    @Test
    @DisplayName("the customer's key is the same every time for a ticket, different for another, and checked exactly")
    void keys() {
        String key = service.linkKey("TKT-AB12CD34");
        assertThat(service.linkKey("TKT-AB12CD34")).isEqualTo(key);
        assertThat(service.linkKey("TKT-ZZ99ZZ99")).isNotEqualTo(key);
        assertThat(service.linkKeyMatches("TKT-AB12CD34", key)).isTrue();
        assertThat(service.linkKeyMatches("TKT-ZZ99ZZ99", key)).isFalse();
        assertThat(service.linkKeyMatches("TKT-AB12CD34", key.substring(1))).isFalse();
        assertThat(service.linkKeyMatches("TKT-AB12CD34", null)).isFalse();
    }

    @Test
    @DisplayName("empty or overlong messages and unknown categories are refused")
    void validation() {
        assertThatThrownBy(() -> service.customerWrite(ticket, "   ")).isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThatThrownBy(() -> service.customerWrite(ticket, "x".repeat(4001))).isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThatThrownBy(() -> SupportService.category("CHAMPS")).isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThat(SupportService.category("boosting")).isEqualTo("BOOSTING");
        assertThat(SupportService.category(null)).isNull();
    }
}
