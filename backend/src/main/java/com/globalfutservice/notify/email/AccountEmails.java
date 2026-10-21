package com.globalfutservice.notify.email;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The account security emails: the password reset link, and "your password was changed".
 *
 * <p>Transactional, like the order emails. They exist because somebody asked for a link or
 * changed their password, and they go whatever the account's marketing preference -- a
 * customer who turned offers off still has to be told their password changed.
 *
 * <p>In the customer's language: the storefront sends it with the request, so the email
 * reads like the page they were on. Spanish and French address the reader as tú and tu,
 * as the storefront does. The branded shell around the content is {@link EmailTemplate}'s
 * and stays as every other GFS email has it.
 */
public final class AccountEmails {

    /** The storefront's three languages. Anything else, or nothing, is English. */
    public enum Lang {
        EN, ES, FR;

        public static Lang of(String code) {
            if (code == null) {
                return EN;
            }
            return switch (code.trim().toLowerCase(Locale.ROOT)) {
                case "es" -> ES;
                case "fr" -> FR;
                default -> EN;
            };
        }
    }

    private AccountEmails() {
    }

    /**
     * The reset link. Also what "Set a password" sends to an account made with Google or
     * Discord, so the words cover choosing a password as well as replacing one.
     */
    public static TransactionalEmails.Rendered resetLink(Lang lang, EmailTemplate.Brand brand,
                                                         String resetUrl, long minutes) {
        String subject;
        String preheader;
        String headline;
        String intro;
        String body;
        String ignore;
        String button;
        String footer;
        switch (lang) {
            case ES -> {
                subject = "Restablece tu contraseña de GFS";
                preheader = "Usa este enlace en los próximos " + minutes + " minutos para elegir una contraseña nueva.";
                headline = "RESTABLECE TU CONTRASEÑA";
                intro = "Hemos recibido una solicitud para elegir una contraseña nueva para tu cuenta de "
                        + "Global FUT Services.";
                body = "Este enlace solo funciona una vez y caduca en " + minutes + " minutos.";
                ignore = "Si no lo has pedido tú, ignora este correo: tu contraseña no cambiará.";
                button = "ELEGIR CONTRASEÑA NUEVA";
                footer = "Recibes este correo porque se pidió un enlace para restablecer la contraseña de tu "
                        + "cuenta de GFS. Es un aviso de seguridad y se envía sean cuales sean tus preferencias "
                        + "de correo.";
            }
            case FR -> {
                subject = "Réinitialise ton mot de passe GFS";
                preheader = "Utilise ce lien dans les " + minutes + " minutes pour choisir un nouveau mot de passe.";
                headline = "RÉINITIALISE TON MOT DE PASSE";
                intro = "Nous avons reçu une demande pour choisir un nouveau mot de passe pour ton compte "
                        + "Global FUT Services.";
                body = "Ce lien ne fonctionne qu'une fois et expire dans " + minutes + " minutes.";
                ignore = "Si tu n'es pas à l'origine de cette demande, ignore cet e-mail : ton mot de passe "
                        + "ne changera pas.";
                button = "CHOISIR UN NOUVEAU MOT DE PASSE";
                footer = "Tu reçois cet e-mail car un lien de réinitialisation du mot de passe a été demandé pour "
                        + "ton compte GFS. C'est un avis de sécurité, envoyé quelles que soient tes préférences "
                        + "e-mail.";
            }
            default -> {
                subject = "Reset your GFS password";
                preheader = "Use this link within " + minutes + " minutes to choose a new password.";
                headline = "RESET YOUR PASSWORD";
                intro = "We received a request to choose a new password for your Global FUT Services account.";
                body = "This link works once and expires in " + minutes + " minutes.";
                ignore = "If you didn't ask for this, you can ignore this email — your password stays the same.";
                button = "CHOOSE A NEW PASSWORD";
                footer = "You're receiving this because a password reset link was requested for your GFS "
                        + "account. It is a security notice, sent whatever your email preferences.";
            }
        }

        EmailTemplate.Content content = new EmailTemplate.Content(
                preheader, "▸", null, headline, intro, List.of(),
                EmailTemplate.paragraphs(body, ignore),
                null, List.of(), null, button, resetUrl, EmailTemplate.esc(footer));

        String text = String.join("\n\n", headline, intro, button + ": " + resetUrl, body, ignore,
                "— Global FUT Services") + "\n";
        return new TransactionalEmails.Rendered(subject, EmailTemplate.render(content, brand), text);
    }

    /**
     * Sent after a password is reset, set or changed. Its button leads to "Forgot password?",
     * the one thing to do if the change was not theirs.
     */
    public static TransactionalEmails.Rendered passwordChanged(Lang lang, EmailTemplate.Brand brand,
                                                               String forgotUrl, Instant changedAt) {
        String when = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm 'UTC'", locale(lang))
                .format(changedAt.atOffset(ZoneOffset.UTC));
        String subject;
        String preheader;
        String headline;
        String intro;
        String whenLabel;
        String yours;
        String notYours;
        String button;
        String footer;
        switch (lang) {
            case ES -> {
                subject = "Se ha cambiado la contraseña de tu cuenta de GFS";
                preheader = "Si has sido tú, no tienes que hacer nada más.";
                headline = "TU CONTRASEÑA SE HA CAMBIADO";
                intro = "Se acaba de cambiar la contraseña de tu cuenta de Global FUT Services y se ha cerrado "
                        + "la sesión en tus otros dispositivos.";
                whenLabel = "Fecha del cambio";
                yours = "Si has sido tú, no tienes que hacer nada más.";
                notYours = "Si no has sido tú, restablece tu contraseña ahora mismo con el botón de abajo y "
                        + "escríbenos por el chat en directo.";
                button = "RESTABLECER CONTRASEÑA";
                footer = "Este es un aviso de seguridad sobre tu cuenta de GFS. Se envía sean cuales sean tus "
                        + "preferencias de correo.";
            }
            case FR -> {
                subject = "Le mot de passe de ton compte GFS a été modifié";
                preheader = "Si c'est bien toi, tu n'as rien d'autre à faire.";
                headline = "TON MOT DE PASSE A ÉTÉ MODIFIÉ";
                intro = "Le mot de passe de ton compte Global FUT Services vient d'être modifié, et tes sessions "
                        + "sur tes autres appareils ont été fermées.";
                whenLabel = "Date du changement";
                yours = "Si c'est bien toi, tu n'as rien d'autre à faire.";
                notYours = "Si ce n'est pas toi, réinitialise ton mot de passe tout de suite avec le bouton "
                        + "ci-dessous, puis contacte-nous sur le chat en direct.";
                button = "RÉINITIALISER LE MOT DE PASSE";
                footer = "Ceci est un avis de sécurité concernant ton compte GFS. Il est envoyé quelles que "
                        + "soient tes préférences e-mail.";
            }
            default -> {
                subject = "Your GFS password was changed";
                preheader = "If this was you, there's nothing else to do.";
                headline = "YOUR PASSWORD WAS CHANGED";
                intro = "The password for your Global FUT Services account was just changed, and you've been "
                        + "signed out on your other devices.";
                whenLabel = "Changed";
                yours = "If this was you, there's nothing else to do.";
                notYours = "If it wasn't you, reset your password straight away with the button below, then "
                        + "contact us on the live chat.";
                button = "RESET PASSWORD";
                footer = "This is a security notice about your GFS account. It is sent whatever your email "
                        + "preferences.";
            }
        }

        EmailTemplate.Content content = new EmailTemplate.Content(
                preheader, "!", null, headline, intro,
                List.of(EmailTemplate.InfoCard.accented("◷", whenLabel, when)),
                EmailTemplate.paragraphs(yours, notYours),
                null, List.of(), null, button, forgotUrl, EmailTemplate.esc(footer));

        String text = String.join("\n\n", headline, intro, whenLabel + ": " + when, yours, notYours,
                button + ": " + forgotUrl, "— Global FUT Services") + "\n";
        return new TransactionalEmails.Rendered(subject, EmailTemplate.render(content, brand), text);
    }

    private static Locale locale(Lang lang) {
        return switch (lang) {
            case ES -> Locale.forLanguageTag("es");
            case FR -> Locale.FRENCH;
            default -> Locale.ENGLISH;
        };
    }
}
