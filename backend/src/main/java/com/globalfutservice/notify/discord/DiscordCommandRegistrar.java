package com.globalfutservice.notify.discord;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.DiscordBotClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells Discord that {@code /verify} exists.
 *
 * <p>A slash command has to be registered before anybody can type it — the command list
 * lives on Discord's side, not in the bot. Registered against the guild rather than
 * globally: a guild command appears the moment this call returns, where a global one
 * takes up to an hour to propagate and would also offer itself in every other server the
 * bot is ever added to.
 *
 * <p>Idempotent. {@code PUT} replaces the guild's command list with exactly what is sent,
 * so running it on every boot converges rather than accumulating duplicates.
 *
 * <p>Failure here is logged and never fatal. A registration that fails costs the command
 * until the next deploy; an exception thrown from startup would cost the whole
 * application, which is a much worse trade for a storefront that mostly sells coins.
 */
@Component
public class DiscordCommandRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DiscordCommandRegistrar.class);

    /** Option type 3 is STRING. */
    private static final int STRING_OPTION = 3;

    private final AppProperties props;
    private final DiscordBotClient bot;

    public DiscordCommandRegistrar(AppProperties props, DiscordBotClient bot) {
        this.props = props;
        this.bot = bot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        AppProperties.Notifications cfg = props.notifications();
        if (!bot.isEnabled() || isBlank(cfg.discordApplicationId())) {
            log.debug("Not registering /verify: the bot or the application id is unset");
            return;
        }

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("name", "order");
        option.put("description", "Your order reference, like GFS-26-XXXXXXXX");
        option.put("type", STRING_OPTION);
        option.put("required", true);

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("name", "verify");
        command.put("description", "Get access to your order's ticket");
        command.put("options", List.of(option));

        try {
            bot.registerGuildCommands(cfg.discordApplicationId(), List.of(command));
            log.info("Registered /verify in guild {}", cfg.discordGuildId());
        } catch (RuntimeException e) {
            log.warn("Could not register /verify; customers will not see the command: {}",
                    e.getMessage());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
