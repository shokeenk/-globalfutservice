package com.globalfutservice.popups;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.globalfutservice.popups.PopupService.PopupInput;
import com.globalfutservice.popups.PopupTypes.Button;
import com.globalfutservice.popups.PopupTypes.Copy;
import com.globalfutservice.popups.PopupTypes.Frequency;
import com.globalfutservice.popups.PopupTypes.Label;
import com.globalfutservice.popups.PopupTypes.LocalizedCopy;
import com.globalfutservice.popups.PopupTypes.Page;
import com.globalfutservice.popups.PopupTypes.Trigger;
import com.globalfutservice.popups.PopupTypes.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a popup must have, and the limits on what it can have. */
class PopupValidationTest {

    static PopupInput valid() {
        return new PopupInput("Coins week", Type.CENTER_MODAL,
                new LocalizedCopy(new Copy("10% off coins", "This week only.", "Invite a friend", "Trusted by thousands"),
                        null, new Copy("10 % sur les pièces", null, null, null)),
                List.of(new Button(new Label("Buy coins", "Comprar", null), "/order")),
                List.of(Page.HOME, Page.COINS), List.of("/help"), Trigger.DELAY, 5, null,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-08T00:00:00Z"), Frequency.DAYS, 7, 10, null, false);
    }

    static PopupInput with(PopupInput v, List<Button> buttons, Trigger trigger, Integer value, String event,
                           Instant starts, Instant ends, Frequency freq, Integer days) {
        return new PopupInput(v.name(), v.type(), v.copy(), buttons, v.targetPages(), v.targetPaths(), trigger, value,
                event, starts, ends, freq, days, v.priority(), null, false);
    }

    private static Map<String, String> problems(PopupInput in) {
        try {
            PopupService.check(in);
            return Map.of();
        } catch (PopupService.PopupInvalid e) {
            return e.problems();
        }
    }

    @Test
    @DisplayName("a complete campaign passes; Spanish and French are optional and English-only fields fall back")
    void validPasses() {
        PopupService.Checked c = PopupService.check(valid());
        assertThat(c.copy().es()).isNull();
        assertThat(c.copy().fr().headline()).isEqualTo("10 % sur les pièces");
        assertThat(c.buttons()).hasSize(1);
    }

    @Test
    @DisplayName("name, type and the English headline are required")
    void required() {
        PopupInput empty = new PopupInput(" ", null, new LocalizedCopy(new Copy(" ", null, null, null), null, null),
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(problems(empty)).containsKeys("name", "type", "copy.en.headline");
    }

    @Test
    @DisplayName("at most two buttons, each with an English label and an allowed destination")
    void buttons() {
        Button ok = new Button(new Label("Go", null, null), "/order");
        assertThat(problems(with(valid(), List.of(ok, ok, ok), Trigger.PAGE_LOAD, null, null, null, null,
                Frequency.SESSION, null))).containsKey("buttons");
        assertThat(problems(with(valid(), List.of(new Button(new Label(null, "Ir", null), "https://evil.example/")),
                Trigger.PAGE_LOAD, null, null, null, null, Frequency.SESSION, null)))
                .containsKeys("buttons.0.label", "buttons.0.destination");
    }

    @Test
    @DisplayName("triggers: seconds 1-600, scroll 1-100%, a step shaped like coins:continue")
    void triggers() {
        assertThat(problems(with(valid(), List.of(), Trigger.DELAY, 0, null, null, null, Frequency.SESSION, null)))
                .containsKey("triggerValue");
        assertThat(problems(with(valid(), List.of(), Trigger.SCROLL, 101, null, null, null, Frequency.SESSION, null)))
                .containsKey("triggerValue");
        assertThat(problems(with(valid(), List.of(), Trigger.STEP, null, "Coins Continue", null, null,
                Frequency.SESSION, null))).containsKey("triggerEvent");
        assertThatCode(() -> PopupService.check(with(valid(), List.of(), Trigger.STEP, null, "champs:checkout", null,
                null, Frequency.SESSION, null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the schedule ends after it starts, and every-N-days needs 1 to 365 days")
    void scheduleAndFrequency() {
        Instant t = Instant.parse("2026-11-01T00:00:00Z");
        assertThat(problems(with(valid(), List.of(), Trigger.PAGE_LOAD, null, null, t, t, Frequency.SESSION, null)))
                .containsKey("endsAt");
        assertThat(problems(with(valid(), List.of(), Trigger.PAGE_LOAD, null, null, null, null, Frequency.DAYS, 0)))
                .containsKey("frequencyDays");
    }

    @Test
    @DisplayName("publishing needs a target page and the desktop image")
    void publishable() {
        PopupService.Checked c = PopupService.check(new PopupInput("x", Type.BOTTOM_RIGHT,
                new LocalizedCopy(new Copy("Hi", null, null, null), null, null), null, null, null, null, null, null,
                null, null, null, null, null, null, null));
        assertThatThrownBy(() -> PopupService.requirePublishable(c, null))
                .isInstanceOfSatisfying(PopupService.PopupInvalid.class,
                        e -> assertThat(e.problems()).containsKeys("targetPages", "desktopImage"));
        assertThatCode(() -> PopupService.requirePublishable(PopupService.check(valid()), 1L)).doesNotThrowAnyException();
    }
}
