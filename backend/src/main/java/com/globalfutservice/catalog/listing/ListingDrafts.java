package com.globalfutservice.catalog.listing;

import com.globalfutservice.domain.catalog.Sku;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * The two hourly Objectives the client asked to have set up for him: Gallery Tokens and
 * Season XP Grind, as inactive drafts with a rupee price, no image and no other currency.
 * He adds the artwork and the other prices, then switches them on.
 *
 * <p>Created at startup rather than by a migration because their price belongs to the
 * season the site is selling ({@code GFS_SEASON}), which a migration cannot know. Created
 * once: a draft already made -- even if renamed or deleted since -- or any listing with the
 * same title is left alone.
 */
@Component
public class ListingDrafts {

    private static final Logger log = LoggerFactory.getLogger(ListingDrafts.class);

    static final String SUBTITLE = "Paid per hour · 100% manual";
    static final long INR_PER_HOUR_MINOR = 65_000L;
    static final String OVERVIEW = "We'll work on your Club manually for the hours you book, focusing on "
            + "what's still needed to progress toward your target.";
    static final String DETAILS = String.join("\n",
            "Everything will be done 100% manually. No bots, bot extensions, automation, or third-party tools "
                    + "will be used.",
            "Before starting, we will analyze your Club and check which Sets/requirements are already completed "
                    + "and what is still needed to progress toward your desired level.",
            "We do not guarantee a specific number of Points, Sets, Tokens, or a particular Level within one hour. "
                    + "Progress depends entirely on your current Club status and available requirements.",
            "As a general reference, we usually complete around 1 Set in approximately 5 minutes, but this can "
                    + "vary depending on the Set.",
            "The required Coins cannot be estimated in advance. After analyzing your Club, we can tell you what "
                    + "needs to be done next and approximately how many Coins may be required based on your "
                    + "current Tokens and target Level.");
    static final String INSTRUCTIONS = "We recommend having at least 400,000 Coins available in your Club so "
            + "there is enough balance to complete the required Sets without interruptions.";

    /** Seed key, then title. */
    static final List<Map.Entry<String, String>> DRAFTS = List.of(
            Map.entry("OBJECTIVES_GALLERY_TOKENS_V1", "Gallery Tokens"),
            Map.entry("OBJECTIVES_SEASON_XP_GRIND_V1", "Season XP Grind"));

    private final ServiceListingService listings;

    public ListingDrafts(ServiceListingService listings) {
        this.listings = listings;
    }

    /** @return how many drafts were created now */
    @Transactional
    public int ensure() {
        int created = 0;
        for (Map.Entry<String, String> draft : DRAFTS) {
            if (listings.seedExists(draft.getKey(), draft.getValue())) {
                continue;
            }
            long id = listings.createSeed(draft.getKey(), form(draft.getValue()));
            log.info("Created the inactive Objectives draft \"{}\" (listing {})", draft.getValue(), id);
            created++;
        }
        return created;
    }

    static ServiceListingService.Form form(String title) {
        return new ServiceListingService.Form(Sku.BOOST_OBJECTIVES.name(), title, SUBTITLE, OVERVIEW, DETAILS,
                null, null, null, null, INSTRUCTIONS, "PER_HOUR", ServiceListingService.DEFAULT_MAX_HOURS, true,
                false, null, Map.of("INR", INR_PER_HOUR_MINOR));
    }
}
