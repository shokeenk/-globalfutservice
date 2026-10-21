package com.globalfutservice.notify.email;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The two account security emails, in each of the storefront's languages. */
class AccountEmailsTest {

    private static final EmailTemplate.Brand BRAND = new EmailTemplate.Brand(
            "https://globalfutservices.com", "globalfutservices.com",
            "https://discord.com/invite/x", "Join the GFS Discord",
            "https://www.instagram.com/global_fut_services/", "@global_fut_services");

    private static final String LINK = "https://globalfutservices.com/reset-password?token=abc_123-XYZ";

    @Test
    @DisplayName("the reset email carries the link as a button and in the text, with its 30 minutes")
    void resetLink() {
        var en = AccountEmails.resetLink(AccountEmails.Lang.EN, BRAND, LINK, 30);
        assertThat(en.subject()).isEqualTo("Reset your GFS password");
        assertThat(en.html()).contains("href=\"" + LINK + "\"").contains("30 minutes");
        assertThat(en.text()).contains(LINK).contains("expires in 30 minutes");
    }

    @Test
    @DisplayName("Spanish says tú and French says tu, and an unknown language falls back to English")
    void languages() {
        var es = AccountEmails.resetLink(AccountEmails.Lang.of("es"), BRAND, LINK, 30);
        var fr = AccountEmails.resetLink(AccountEmails.Lang.of("FR"), BRAND, LINK, 30);
        var de = AccountEmails.resetLink(AccountEmails.Lang.of("de"), BRAND, LINK, 30);
        assertThat(es.subject()).isEqualTo("Restablece tu contraseña de GFS");
        assertThat(es.text()).contains("caduca en 30 minutos").contains(LINK);
        assertThat(fr.subject()).isEqualTo("Réinitialise ton mot de passe GFS");
        assertThat(fr.text()).contains("expire dans 30 minutes").contains(LINK);
        assertThat(de.subject()).isEqualTo("Reset your GFS password");
        assertThat(AccountEmails.Lang.of(null)).isEqualTo(AccountEmails.Lang.EN);
    }

    @Test
    @DisplayName("the password-changed notice says when, and leads to Forgot password")
    void passwordChanged() {
        Instant at = Instant.parse("2026-10-21T10:05:00Z");
        String forgot = "https://globalfutservices.com/forgot-password";
        var en = AccountEmails.passwordChanged(AccountEmails.Lang.EN, BRAND, forgot, at);
        var es = AccountEmails.passwordChanged(AccountEmails.Lang.ES, BRAND, forgot, at);
        var fr = AccountEmails.passwordChanged(AccountEmails.Lang.FR, BRAND, forgot, at);

        assertThat(en.subject()).isEqualTo("Your GFS password was changed");
        assertThat(en.text()).contains("21 Oct 2026, 10:05 UTC").contains(forgot);
        assertThat(en.html()).contains("href=\"" + forgot + "\"").contains("whatever your email preferences");
        assertThat(es.subject()).isEqualTo("Se ha cambiado la contraseña de tu cuenta de GFS");
        assertThat(fr.subject()).isEqualTo("Le mot de passe de ton compte GFS a été modifié");
        assertThat(fr.html()).contains("tu n&#39;as rien d&#39;autre à faire");
    }
}
