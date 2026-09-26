package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The recipient list: written once, when a campaign is first claimed.
 */
class CampaignSenderListTest {

    private CampaignRecipientRepository recipients;
    private CampaignSender sender;

    @BeforeEach
    void setUp() {
        recipients = mock(CampaignRecipientRepository.class);
        sender = new CampaignSender(mock(CampaignRepository.class), recipients,
                mock(AccountRepository.class), mock(JavaMailSender.class),
                mock(AppProperties.class), mock(CampaignRenderer.class));
    }

    private static AccountEntity person(long id, String email) {
        AccountEntity a = mock(AccountEntity.class);
        when(a.getId()).thenReturn(id);
        when(a.getEmail()).thenReturn(email);
        return a;
    }

    @Test
    @DisplayName("lists everybody on the first claim")
    @SuppressWarnings("unchecked")
    void firstClaim() {
        when(recipients.countByCampaignId(4L)).thenReturn(0L);

        int listed = sender.buildRecipients(4L,
                List.of(person(12, "a@example.test"), person(13, "b@example.test")));

        ArgumentCaptor<Iterable<CampaignRecipientEntity>> rows = ArgumentCaptor.forClass(Iterable.class);
        verify(recipients).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(CampaignRecipientEntity::getAccountId)
                .containsExactly(12L, 13L);
        assertThat(rows.getValue()).extracting(CampaignRecipientEntity::getStatus)
                .containsOnly("PENDING");
        assertThat(listed).isEqualTo(2);
    }

    @Test
    @DisplayName("adds nobody when the campaign already has its list")
    void listIsFixed() {
        when(recipients.countByCampaignId(4L)).thenReturn(2L);

        int listed = sender.buildRecipients(4L,
                List.of(person(12, "a@example.test"), person(13, "b@example.test"),
                        person(14, "joined-since@example.test")));

        verify(recipients, never()).saveAll(anyIterable());
        assertThat(listed).isEqualTo(2);
    }

    @Test
    @DisplayName("lets a clash propagate rather than swallowing it")
    void clashIsLoud() {
        when(recipients.countByCampaignId(4L)).thenReturn(0L);
        when(recipients.saveAll(anyIterable()))
                .thenThrow(new DataIntegrityViolationException("email_campaign_recipient_unique"));

        assertThatThrownBy(() -> sender.buildRecipients(4L, List.of(person(12, "a@example.test"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
