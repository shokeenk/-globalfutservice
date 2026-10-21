package com.globalfutservice.popups;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.globalfutservice.popups.PopupTypes.Button;
import com.globalfutservice.popups.PopupTypes.Frequency;
import com.globalfutservice.popups.PopupTypes.LocalizedCopy;
import com.globalfutservice.popups.PopupTypes.Page;
import com.globalfutservice.popups.PopupTypes.Trigger;
import com.globalfutservice.popups.PopupTypes.Type;
import com.globalfutservice.security.AttemptThrottle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the storefront is given: the campaigns that are published, switched on and inside
 * their schedule, for the page it is on. And the three counters it reports back.
 *
 * <p>The live list is read from the database at most every {@link #CACHE} and filtered per
 * request, so a busy page costs one query per half-minute, not one per visitor. The
 * schedule is checked again on every request against the clock, so a campaign stops being
 * served the moment it ends, cache or not.
 */
@Service
public class PopupPublicService {

    static final Duration CACHE = Duration.ofSeconds(30);

    /** Counters reported per IP: a minute's worth, refilling. Beyond it they are dropped quietly. */
    private final AttemptThrottle events = new AttemptThrottle(60, Duration.ofMinutes(1));

    public enum Event { VIEW, CLOSE, CLICK }

    public record PublicImage(String url, int width, int height) {
    }

    /** A live campaign as the storefront needs it: no name, no stats, nothing internal. */
    public record PublicPopup(String id, int version, Type type, PublicImage desktopImage, PublicImage mobileImage,
                              LocalizedCopy copy, List<Button> buttons, List<Page> targetPages,
                              List<String> targetPaths, Trigger triggerType, Integer triggerValue,
                              String triggerEvent, Frequency frequency, Integer frequencyDays, int priority) {
    }

    /** A cached campaign with the schedule kept beside it, to re-check per request. */
    record Live(PublicPopup popup, Instant startsAt, Instant endsAt) {
        boolean inSchedule(Instant now) {
            return (startsAt == null || !startsAt.isAfter(now)) && (endsAt == null || endsAt.isAfter(now));
        }
    }

    private record Snapshot(Instant at, List<Live> live) {
    }

    private final PopupCampaignRepository campaigns;
    private final PopupService service;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Snapshot snapshot;

    public PopupPublicService(PopupCampaignRepository campaigns, PopupService service, JdbcTemplate jdbc,
                              Clock clock) {
        this.campaigns = campaigns;
        this.service = service;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * The campaigns for a page: those targeting its key, or its path under "selected pages".
     * Either may be absent; with neither, nothing.
     */
    public List<PublicPopup> forPage(Page page, String path) {
        Instant now = clock.instant();
        String normalised = path == null ? null : normalise(path);
        return live(now).stream()
                .filter(l -> l.inSchedule(now))
                .map(Live::popup)
                .filter(p -> (page != null && p.targetPages().contains(page))
                        || (normalised != null && p.targetPaths().stream().anyMatch(t -> normalise(t).equals(normalised))))
                .toList();
    }

    /**
     * One view, close or click. Counted only for a campaign that is live now and only within
     * the caller's budget; anything else is ignored, never an error a script could probe.
     */
    @Transactional
    public void record(String publicId, Event event, String ip) {
        if (!events.record("ip:" + ip)) {
            return;
        }
        Instant now = clock.instant();
        Optional<PopupCampaignEntity> found = campaigns.findByPublicIdAndDeletedAtIsNull(publicId);
        if (found.isEmpty() || !found.get().isLive(now)) {
            return;
        }
        String column = switch (event) {
            case VIEW -> "views";
            case CLOSE -> "closes";
            case CLICK -> "clicks";
        };
        jdbc.update("insert into popup_stat_daily (campaign_id, day, " + column + ") values (?, ?, 1) "
                + "on conflict (campaign_id, day) do update set " + column + " = popup_stat_daily." + column + " + 1",
                found.get().getId(), LocalDate.ofInstant(now, ZoneOffset.UTC));
    }

    /** Forget the cached list: an admin changed something. */
    public void invalidate() {
        snapshot = null;
    }

    private List<Live> live(Instant now) {
        Snapshot s = snapshot;
        if (s != null && s.at().plus(CACHE).isAfter(now)) {
            return s.live();
        }
        List<Live> fresh = load();
        snapshot = new Snapshot(now, fresh);
        return fresh;
    }

    @Transactional(readOnly = true)
    List<Live> load() {
        return campaigns.findSwitchedOn().stream()
                .map(c -> new Live(new PublicPopup(c.getPublicId(), c.getVersion(), c.getType(),
                        image(c.getDesktopImageId()), image(c.getMobileImageId()), service.copyOf(c),
                        service.buttonsOf(c), service.pagesOf(c), service.pathsOf(c), c.getTriggerType(),
                        c.getTriggerValue(), c.getTriggerEvent(), c.getFrequency(), c.getFrequencyDays(),
                        c.getPriority()), c.getStartsAt(), c.getEndsAt()))
                .toList();
    }

    private PublicImage image(Long id) {
        PopupService.ImageView v = service.image(id);
        return v == null ? null : new PublicImage(v.url(), v.width(), v.height());
    }

    private static String normalise(String path) {
        String p = path.trim().toLowerCase(Locale.ROOT);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }
}
