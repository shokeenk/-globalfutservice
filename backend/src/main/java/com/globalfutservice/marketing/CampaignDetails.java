package com.globalfutservice.marketing;

import java.time.LocalDate;

/**
 * Everything the campaign builder's first step collects, as one value.
 *
 * <p>Saved whole rather than field by field. The builder always sends the complete step,
 * so a field left empty means "empty", not "unchanged" — which is what lets an admin
 * clear an offer date or remove a promo code. The older editor's partial update keeps
 * its own "null means leave it" semantics and does not go through this.
 *
 * @param promoTitle   the headline inside the email; stored as the campaign's heading
 * @param description  the paragraph beneath the hero; stored as the campaign's body
 * @param showButton   whether the email carries an "ORDER NOW" button, whose
 *                     destination follows {@code type}
 */
public record CampaignDetails(
        String title,
        String subject,
        CampaignType type,
        String promoTitle,
        String offerText,
        String promoCode,
        LocalDate offerValidUntil,
        String description,
        boolean showButton,
        boolean showPromoCode,
        boolean trackingEnabled) {
}
