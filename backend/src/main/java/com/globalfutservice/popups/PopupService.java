package com.globalfutservice.popups;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.popups.PopupTypes.Button;
import com.globalfutservice.popups.PopupTypes.Copy;
import com.globalfutservice.popups.PopupTypes.Frequency;
import com.globalfutservice.popups.PopupTypes.Label;
import com.globalfutservice.popups.PopupTypes.LocalizedCopy;
import com.globalfutservice.popups.PopupTypes.Page;
import com.globalfutservice.popups.PopupTypes.Status;
import com.globalfutservice.popups.PopupTypes.Trigger;
import com.globalfutservice.popups.PopupTypes.Type;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Popup Manager: create, edit, duplicate, delete, images, publish and switch on or off.
 * Admins only (the controller says so). Every change is written to {@code popup_change}
 * and the log, with who made it.
 *
 * <p>A campaign starts as a DRAFT, switched off. It reaches the storefront only once it is
 * PUBLISHED, switched on, and inside its schedule -- two separate decisions, so publishing
 * an edit never also turns something on.
 *
 * <p>Nothing here touches prices, coupons, points or an order. A popup is words, a picture
 * and at most two links.
 */
@Service
public class PopupService {

    private static final Logger log = LoggerFactory.getLogger(PopupService.class);

    private static final Pattern STEP = Pattern.compile("^[a-z0-9]+:[a-z0-9-]+$");

    /** What an admin sends to create or edit a campaign. Images go separately. */
    public record PopupInput(
            String name,
            Type type,
            LocalizedCopy copy,
            List<Button> buttons,
            List<Page> targetPages,
            List<String> targetPaths,
            Trigger triggerType,
            Integer triggerValue,
            String triggerEvent,
            Instant startsAt,
            Instant endsAt,
            Frequency frequency,
            Integer frequencyDays,
            Integer priority,
            /** DELAY_OR_SCROLL's percentage; its seconds go in {@code triggerValue}. */
            Integer triggerScroll,
            /** Never on a checkout page. */
            Boolean excludeCheckout) {
    }

    public record ImageView(long id, String url, String contentType, int width, int height, int sizeBytes) {
    }

    public record Stats(long views, long closes, long clicks, long views30d, long closes30d, long clicks30d) {
    }

    /** A campaign as the Popup Manager shows it. */
    public record PopupView(
            String id, String name, Type type, Status status, boolean enabled, int version,
            ImageView desktopImage, ImageView mobileImage,
            LocalizedCopy copy, List<Button> buttons, List<Page> targetPages, List<String> targetPaths,
            Trigger triggerType, Integer triggerValue, String triggerEvent,
            Instant startsAt, Instant endsAt, Frequency frequency, Integer frequencyDays, int priority,
            boolean live, Instant createdAt, Instant updatedAt, Stats stats,
            Integer triggerScroll, boolean excludeCheckout, PopupTypes.Preset preset) {
    }

    public record Change(String action, String detail, Long adminId, String adminEmail, Instant at) {
    }

    private final PopupCampaignRepository campaigns;
    private final PopupImageStore images;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PopupService(PopupCampaignRepository campaigns, PopupImageStore images, JdbcTemplate jdbc,
                        ObjectMapper mapper, Clock clock) {
        this.campaigns = campaigns;
        this.images = images;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<PopupView> list() {
        Map<Long, Stats> stats = stats();
        Instant now = clock.instant();
        return campaigns.findAllByDeletedAtIsNullOrderByUpdatedAtDesc().stream()
                .map(c -> view(c, stats.getOrDefault(c.getId(), new Stats(0, 0, 0, 0, 0, 0)), now))
                .toList();
    }

    @Transactional(readOnly = true)
    public PopupView get(String publicId) {
        PopupCampaignEntity c = require(publicId);
        return view(c, stats().getOrDefault(c.getId(), new Stats(0, 0, 0, 0, 0, 0)), clock.instant());
    }

    @Transactional(readOnly = true)
    public List<Change> changes(String publicId) {
        PopupCampaignEntity c = require(publicId);
        return jdbc.query("""
                select ch.action, ch.detail, ch.admin_id, a.email, ch.at from popup_change ch
                  left join account a on a.id = ch.admin_id
                 where ch.campaign_id = ? order by ch.at desc, ch.id desc limit 200
                """, (rs, i) -> new Change(rs.getString(1), rs.getString(2), (Long) rs.getObject(3), rs.getString(4),
                rs.getTimestamp(5).toInstant()), c.getId());
    }

    // ----------------------------------------------------------------- writes

    @Transactional
    public PopupView create(PopupInput input, Long adminId) {
        Checked ok = check(input, null);
        Instant now = clock.instant();
        PopupCampaignEntity c = new PopupCampaignEntity("pop_" + SecureIds.token(9), ok.name, ok.type,
                json(ok.copy), adminId, now);
        apply(c, ok);
        c = campaigns.save(c);
        record(c, "CREATED", "Draft, switched off", adminId, now);
        return get(c.getPublicId());
    }

    @Transactional
    public PopupView update(String publicId, PopupInput input, Long adminId) {
        PopupCampaignEntity c = require(publicId);
        Checked ok = check(input, c.getPreset());
        if (c.getStatus() == Status.PUBLISHED) {
            requirePublishable(ok, c.getDesktopImageId());
        }
        Instant now = clock.instant();
        apply(c, ok);
        c.touched(adminId, now);
        campaigns.save(c);
        record(c, "EDITED", "Version " + c.getVersion(), adminId, now);
        return get(publicId);
    }

    /** A copy as a new draft, switched off, with the same images. */
    @Transactional
    public PopupView duplicate(String publicId, Long adminId) {
        PopupCampaignEntity source = require(publicId);
        Instant now = clock.instant();
        PopupCampaignEntity copy = new PopupCampaignEntity("pop_" + SecureIds.token(9),
                truncate("Copy of " + source.getName(), 120), source.getType(), source.getCopy(), adminId, now);
        copy.setButtons(source.getButtons());
        copy.setTargetPages(source.getTargetPages());
        copy.setTargetPaths(source.getTargetPaths());
        copy.setTriggerType(source.getTriggerType());
        copy.setTriggerValue(source.getTriggerValue());
        copy.setTriggerEvent(source.getTriggerEvent());
        copy.setStartsAt(source.getStartsAt());
        copy.setEndsAt(source.getEndsAt());
        copy.setFrequency(source.getFrequency());
        copy.setFrequencyDays(source.getFrequencyDays());
        copy.setPriority(source.getPriority());
        copy.setTriggerScroll(source.getTriggerScroll());
        copy.setExcludeCheckout(source.isExcludeCheckout());
        // A copy of a guide popup keeps its rules: no buttons where it had none.
        copy.setPreset(source.getPreset());
        copy = campaigns.save(copy);
        // The images too, as rows of their own, so deleting either campaign leaves the other whole.
        copy.setDesktopImageId(copyImage(source.getDesktopImageId(), copy.getId(), adminId));
        copy.setMobileImageId(copyImage(source.getMobileImageId(), copy.getId(), adminId));
        campaigns.save(copy);
        record(copy, "CREATED", "Duplicated from " + source.getPublicId() + " (" + source.getName() + ")",
                adminId, now);
        record(source, "DUPLICATED", "As " + copy.getPublicId(), adminId, now);
        return get(copy.getPublicId());
    }

    /** Off the storefront and out of the list for good; counters and log are kept, images removed. */
    @Transactional
    public void delete(String publicId, Long adminId) {
        PopupCampaignEntity c = require(publicId);
        Instant now = clock.instant();
        c.setEnabled(false);
        c.setStatus(Status.DRAFT);
        c.setDesktopImageId(null);
        c.setMobileImageId(null);
        c.setDeletedAt(now);
        c.touched(adminId, now);
        campaigns.saveAndFlush(c);
        images.delete(images.idsFor(c.getId()));
        record(c, "DELETED", c.getName(), adminId, now);
    }

    @Transactional
    public PopupView publish(String publicId, Long adminId) {
        PopupCampaignEntity c = require(publicId);
        requirePublishable(check(input(c), c.getPreset()), c.getDesktopImageId());
        Instant now = clock.instant();
        c.setStatus(Status.PUBLISHED);
        c.touched(adminId, now);
        campaigns.save(c);
        record(c, "PUBLISHED", c.isEnabled() ? "Switched on" : "Still switched off", adminId, now);
        return get(publicId);
    }

    @Transactional
    public PopupView unpublish(String publicId, Long adminId) {
        PopupCampaignEntity c = require(publicId);
        Instant now = clock.instant();
        c.setStatus(Status.DRAFT);
        c.touched(adminId, now);
        campaigns.save(c);
        record(c, "UNPUBLISHED", null, adminId, now);
        return get(publicId);
    }

    @Transactional
    public PopupView setEnabled(String publicId, boolean enabled, Long adminId) {
        PopupCampaignEntity c = require(publicId);
        if (c.isEnabled() == enabled) {
            return get(publicId);
        }
        Instant now = clock.instant();
        c.setEnabled(enabled);
        c.touched(adminId, now);
        campaigns.save(c);
        record(c, enabled ? "SWITCHED_ON" : "SWITCHED_OFF", null, adminId, now);
        return get(publicId);
    }

    /**
     * Uploads or replaces the desktop or mobile image. Read to at most 1.5 MB plus one byte,
     * so an over-size file is refused without reading all of it, and checked by its bytes.
     */
    @Transactional
    public PopupView putImage(String publicId, boolean mobile, MultipartFile file, Long adminId) {
        PopupCampaignEntity c = require(publicId);
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readNBytes(PopupImageCheck.MAX_BYTES + 1);
        } catch (IOException e) {
            throw new ApiExceptions.BadRequestException("image_unreadable", "That upload could not be read.");
        }
        PopupImageCheck.Image image;
        try {
            image = PopupImageCheck.check(bytes);
        } catch (PopupImageCheck.Refused e) {
            throw new ApiExceptions.BadRequestException("image_refused", e.getMessage());
        }
        PopupImageStore.ImageRow stored = images.store(c.getId(), bytes, image, adminId);
        Long previous = mobile ? c.getMobileImageId() : c.getDesktopImageId();
        Instant now = clock.instant();
        if (mobile) {
            c.setMobileImageId(stored.id());
        } else {
            c.setDesktopImageId(stored.id());
        }
        c.touched(adminId, now);
        campaigns.saveAndFlush(c);
        if (previous != null) {
            images.delete(List.of(previous));
        }
        record(c, mobile ? "MOBILE_IMAGE" : "DESKTOP_IMAGE", image.contentType() + ", " + image.width() + " × "
                + image.height() + " px, " + Math.round(bytes.length / 1024.0) + " KB", adminId, now);
        return get(publicId);
    }

    /** The mobile image is optional; the desktop image can only be replaced. */
    @Transactional
    public PopupView removeMobileImage(String publicId, Long adminId) {
        PopupCampaignEntity c = require(publicId);
        Long previous = c.getMobileImageId();
        if (previous == null) {
            return get(publicId);
        }
        Instant now = clock.instant();
        c.setMobileImageId(null);
        c.touched(adminId, now);
        campaigns.saveAndFlush(c);
        images.delete(List.of(previous));
        record(c, "MOBILE_IMAGE_REMOVED", null, adminId, now);
        return get(publicId);
    }

    // ------------------------------------------------------------- checking

    /** An input that has passed {@link #check}: trimmed, defaulted, every value in range. */
    record Checked(String name, Type type, LocalizedCopy copy, List<Button> buttons, List<Page> targetPages,
                   List<String> targetPaths, Trigger triggerType, Integer triggerValue, String triggerEvent,
                   Instant startsAt, Instant endsAt, Frequency frequency, Integer frequencyDays, int priority,
                   Integer triggerScroll, boolean excludeCheckout) {
    }

    /** Every rule, for a popup with no preset. */
    static Checked check(PopupInput in) {
        return check(in, null);
    }

    /** Every rule, with every problem named at once, keyed by field. */
    static Checked check(PopupInput in, PopupTypes.Preset preset) {
        Map<String, String> problems = new LinkedHashMap<>();
        String name = trim(in.name());
        if (name == null) problems.put("name", "Give the popup a name.");
        else if (name.length() > 120) problems.put("name", "Use at most 120 characters.");
        if (in.type() == null) problems.put("type", "Choose centred or bottom-right.");

        LocalizedCopy rawCopy = in.copy() == null ? new LocalizedCopy(null, null, null) : in.copy();
        Copy en = copy(rawCopy.en(), "copy.en", true, problems);
        Copy es = copy(rawCopy.es(), "copy.es", false, problems);
        Copy fr = copy(rawCopy.fr(), "copy.fr", false, problems);

        List<Button> buttons = new ArrayList<>();
        List<Button> rawButtons = in.buttons() == null ? List.of() : in.buttons();
        if (rawButtons.size() > PopupTypes.MAX_BUTTONS) {
            problems.put("buttons", "At most " + PopupTypes.MAX_BUTTONS + " buttons.");
        }
        if (preset != null && !preset.allowsButtons() && !rawButtons.isEmpty()) {
            problems.put("buttons", "This popup has no buttons, as the guide sets it out.");
        }
        for (int i = 0; i < Math.min(rawButtons.size(), PopupTypes.MAX_BUTTONS); i++) {
            Button b = rawButtons.get(i);
            String key = "buttons." + i;
            Label l = b == null || b.label() == null ? new Label(null, null, null) : b.label();
            String labelEn = trim(l.en());
            if (labelEn == null) problems.put(key + ".label", "Every button needs an English label.");
            for (String label : new String[] {labelEn, trim(l.es()), trim(l.fr())}) {
                if (label != null && label.length() > 40) problems.put(key + ".label", "Labels are at most 40 characters.");
            }
            String destination = b == null ? null : b.destination();
            PopupDestinations.problem(destination).ifPresent(p -> problems.put(key + ".destination", p));
            buttons.add(new Button(new Label(labelEn, trim(l.es()), trim(l.fr())),
                    destination == null ? null : destination.trim()));
        }

        List<Page> pages = in.targetPages() == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(in.targetPages().stream().filter(p -> p != null).toList()));
        List<String> paths = new ArrayList<>();
        for (String p : in.targetPaths() == null ? List.<String>of() : in.targetPaths()) {
            String path = trim(p);
            if (path == null) continue;
            if (!PopupDestinations.isPagePath(path)) {
                problems.put("targetPaths", "\"" + path + "\" is not a page on this site, e.g. /help.");
            } else if (!paths.contains(path)) {
                paths.add(path);
            }
        }
        if (paths.size() > 20) problems.put("targetPaths", "At most 20 pages.");

        Trigger trigger = in.triggerType() == null ? Trigger.PAGE_LOAD : in.triggerType();
        Integer value = null;
        Integer scroll = null;
        String event = null;
        switch (trigger) {
            case DELAY -> {
                value = in.triggerValue();
                if (value == null || value < 1 || value > 600) problems.put("triggerValue", "Between 1 and 600 seconds.");
            }
            case SCROLL -> {
                value = in.triggerValue();
                if (value == null || value < 1 || value > 100) problems.put("triggerValue", "Between 1% and 100%.");
            }
            case DELAY_OR_SCROLL -> {
                value = in.triggerValue();
                scroll = in.triggerScroll();
                if (value == null || value < 1 || value > 600) problems.put("triggerValue", "Between 1 and 600 seconds.");
                if (scroll == null || scroll < 1 || scroll > 100) problems.put("triggerScroll", "Between 1% and 100%.");
            }
            case STEP -> {
                event = trim(in.triggerEvent());
                if (event == null || event.length() > 60 || !STEP.matcher(event).matches()) {
                    problems.put("triggerEvent", "A step name like coins:continue.");
                }
            }
            case PAGE_LOAD -> { }
        }

        if (in.startsAt() != null && in.endsAt() != null && !in.endsAt().isAfter(in.startsAt())) {
            problems.put("endsAt", "The end must be after the start.");
        }
        Frequency frequency = in.frequency() == null ? Frequency.SESSION : in.frequency();
        Integer days = null;
        if (frequency == Frequency.DAYS) {
            days = in.frequencyDays();
            if (days == null || days < 1 || days > 365) problems.put("frequencyDays", "Between 1 and 365 days.");
        }
        int priority = in.priority() == null ? 0 : in.priority();
        if (priority < 0 || priority > 1000) problems.put("priority", "Between 0 and 1000.");

        if (!problems.isEmpty()) {
            throw new PopupInvalid(problems);
        }
        return new Checked(name, in.type(), new LocalizedCopy(en, es, fr), List.copyOf(buttons), pages,
                List.copyOf(paths), trigger, value, event, in.startsAt(), in.endsAt(), frequency, days, priority,
                scroll, Boolean.TRUE.equals(in.excludeCheckout()));
    }

    /** Publishing also needs somewhere to show and a picture to show. */
    static void requirePublishable(Checked c, Long desktopImageId) {
        Map<String, String> problems = new LinkedHashMap<>();
        if (c.targetPages().isEmpty() && c.targetPaths().isEmpty()) {
            problems.put("targetPages", "Choose at least one page before publishing.");
        }
        if (desktopImageId == null) {
            problems.put("desktopImage", "Upload the desktop image before publishing.");
        }
        if (!problems.isEmpty()) {
            throw new PopupInvalid(problems);
        }
    }

    private static Copy copy(Copy raw, String key, boolean required, Map<String, String> problems) {
        Copy c = raw == null ? new Copy(null, null, null, null) : raw;
        String headline = trim(c.headline());
        String body = trim(c.body());
        String referral = trim(c.referral());
        String trust = trim(c.trust());
        if (required && headline == null) problems.put(key + ".headline", "The English headline is required.");
        if (headline != null && headline.length() > 120) problems.put(key + ".headline", "At most 120 characters.");
        if (body != null && body.length() > 600) problems.put(key + ".body", "At most 600 characters.");
        if (referral != null && referral.length() > 200) problems.put(key + ".referral", "At most 200 characters.");
        if (trust != null && trust.length() > 200) problems.put(key + ".trust", "At most 200 characters.");
        if (!required && headline == null && body == null && referral == null && trust == null) {
            return null;
        }
        return new Copy(headline, body, referral, trust);
    }

    /** A 400 that names each field, the shape the admin forms already read. */
    public static class PopupInvalid extends RuntimeException {
        private final Map<String, String> problems;

        public PopupInvalid(Map<String, String> problems) {
            super("Please check the highlighted fields.");
            this.problems = Map.copyOf(problems);
        }

        public Map<String, String> problems() {
            return problems;
        }
    }

    // --------------------------------------------------------------- helpers

    private void apply(PopupCampaignEntity c, Checked ok) {
        c.setName(ok.name());
        c.setType(ok.type());
        c.setCopy(json(ok.copy()));
        c.setButtons(json(ok.buttons()));
        c.setTargetPages(json(ok.targetPages()));
        c.setTargetPaths(json(ok.targetPaths()));
        c.setTriggerType(ok.triggerType());
        c.setTriggerValue(ok.triggerValue());
        c.setTriggerEvent(ok.triggerEvent());
        c.setStartsAt(ok.startsAt());
        c.setEndsAt(ok.endsAt());
        c.setFrequency(ok.frequency());
        c.setFrequencyDays(ok.frequencyDays());
        c.setPriority(ok.priority());
        c.setTriggerScroll(ok.triggerScroll());
        c.setExcludeCheckout(ok.excludeCheckout());
    }

    /** The stored campaign as an input again, for re-checking at publish time. */
    PopupInput input(PopupCampaignEntity c) {
        return new PopupInput(c.getName(), c.getType(), copyOf(c), buttonsOf(c), pagesOf(c), pathsOf(c),
                c.getTriggerType(), c.getTriggerValue(), c.getTriggerEvent(), c.getStartsAt(), c.getEndsAt(),
                c.getFrequency(), c.getFrequencyDays(), c.getPriority(), c.getTriggerScroll(), c.isExcludeCheckout());
    }

    PopupView view(PopupCampaignEntity c, Stats stats, Instant now) {
        return new PopupView(c.getPublicId(), c.getName(), c.getType(), c.getStatus(), c.isEnabled(), c.getVersion(),
                image(c.getDesktopImageId()), image(c.getMobileImageId()), copyOf(c), buttonsOf(c), pagesOf(c),
                pathsOf(c), c.getTriggerType(), c.getTriggerValue(), c.getTriggerEvent(), c.getStartsAt(),
                c.getEndsAt(), c.getFrequency(), c.getFrequencyDays(), c.getPriority(), c.isLive(now),
                c.getCreatedAt(), c.getUpdatedAt(), stats, c.getTriggerScroll(), c.isExcludeCheckout(), c.getPreset());
    }

    ImageView image(Long id) {
        if (id == null) return null;
        return images.find(id).map(i -> new ImageView(i.id(), imageUrl(i), i.contentType(), i.width(), i.height(),
                i.sizeBytes())).orElse(null);
    }

    /** The versioned, cache-for-ever address: id, then the hash, then the extension. */
    public static String imageUrl(PopupImageStore.ImageRow i) {
        return "/api/v1/popups/images/" + i.id() + "/" + i.version() + "." + i.extension();
    }

    LocalizedCopy copyOf(PopupCampaignEntity c) {
        return read(c.getCopy(), new TypeReference<>() { });
    }

    List<Button> buttonsOf(PopupCampaignEntity c) {
        return read(c.getButtons(), new TypeReference<>() { });
    }

    List<Page> pagesOf(PopupCampaignEntity c) {
        return read(c.getTargetPages(), new TypeReference<>() { });
    }

    List<String> pathsOf(PopupCampaignEntity c) {
        return read(c.getTargetPaths(), new TypeReference<>() { });
    }

    private Long copyImage(Long id, long campaignId, Long adminId) {
        if (id == null) return null;
        Long copied = jdbc.queryForObject("""
                insert into popup_image (campaign_id, content_type, size_bytes, width, height, sha256, data_oid,
                                         uploaded_by)
                select ?, content_type, size_bytes, width, height, sha256, lo_from_bytea(0, lo_get(data_oid)), ?
                  from popup_image where id = ? returning id
                """, Long.class, campaignId, adminId, id);
        return copied;
    }

    private Map<Long, Stats> stats() {
        LocalDate since = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(29);
        Map<Long, Stats> out = new LinkedHashMap<>();
        jdbc.query("""
                select campaign_id, sum(views), sum(closes), sum(clicks),
                       sum(views) filter (where day >= ?), sum(closes) filter (where day >= ?),
                       sum(clicks) filter (where day >= ?)
                  from popup_stat_daily group by campaign_id
                """, rs -> {
            out.put(rs.getLong(1), new Stats(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                    rs.getLong(6), rs.getLong(7)));
        }, since, since, since);
        return out;
    }

    private PopupCampaignEntity require(String publicId) {
        return campaigns.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such popup."));
    }

    private void record(PopupCampaignEntity c, String action, String detail, Long adminId, Instant now) {
        jdbc.update("insert into popup_change (campaign_id, action, detail, admin_id, at) values (?, ?, ?, ?, ?)",
                c.getId(), action, detail, adminId, java.sql.Timestamp.from(now));
        log.info("Popup {} {} by account {}{}", c.getPublicId(), action, adminId, detail == null ? "" : ": " + detail);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String value) {
        if (value == null) return null;
        String t = value.strip();
        return t.isEmpty() ? null : t;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
