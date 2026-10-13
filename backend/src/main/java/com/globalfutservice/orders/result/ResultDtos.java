package com.globalfutservice.orders.result;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** What the result endpoints take and return, for the customer and for staff. */
public final class ResultDtos {

    private ResultDtos() {
    }

    /**
     * The result on the customer's tracking page. Nothing staff-only: no notes, no proof, no
     * names. Amounts are in the order's currency.
     *
     * @param settlementFormatted what is owed; null when nothing is
     * @param chooseBy            until when the customer can choose; null once a method is set
     * @param refundAllowed       false when the order was paid partly with store credit and the
     *                            settlement is more than the money paid: then only credit
     * @param defaultMethod       what they get if they do not choose
     */
    public record CustomerView(
            String kind,
            int targetLevel,
            int achievedLevel,
            boolean belowLowest,
            boolean allAchieved,
            Long settlementMinor,
            String settlementFormatted,
            String method,
            String status,
            Instant chooseBy,
            boolean refundAllowed,
            String defaultMethod) {
    }

    /** What delivering a Champions order records: the wins, and below the lowest tier an amount and why. */
    public record ResultInput(
            @PositiveOrZero Integer achievedLevel,
            Boolean belowLowest,
            @PositiveOrZero Long manualAmountMinor,
            @Size(max = 500, message = "Keep the note under 500 characters") String note) {

        public boolean below() {
            return Boolean.TRUE.equals(belowLowest);
        }
    }

    /** A change before the settlement is paid or issued. Any field left null is left as it is. */
    public record ResultUpdate(
            @PositiveOrZero Integer achievedLevel,
            Boolean belowLowest,
            @PositiveOrZero Long manualAmountMinor,
            /** REFUND or STORE_CREDIT. Admin only. */
            String method,
            @Size(max = 500, message = "Keep the note under 500 characters") String note) {
    }

    /** Confirms a refund has actually been sent: how, and its reference. */
    public record PaidRequest(
            @NotBlank(message = "How was the refund sent?") String method,
            @NotBlank(message = "The refund's reference is required")
            @Size(max = 120, message = "Keep the reference under 120 characters") String reference,
            boolean confirmed) {
    }

    public record IssueRequest(boolean confirmed) {
    }

    public record ChoiceRequest(@NotBlank(message = "Choose a refund or GFS Store Credit") String method) {
    }

    /**
     * One level an admin can choose, with what it would settle to and the tier difference it
     * comes from -- the card shows the calculation, worked by the server.
     */
    public record Option(int level, long achievedPriceMinor, String achievedPriceFormatted,
                         String differenceFormatted, long settlementMinor, String settlementFormatted,
                         boolean refundAllowed) {
    }

    public record Proof(String contentType, int sizeBytes, Instant uploadedAt) {
    }

    /** The result as recorded, for staff. */
    public record Recorded(
            int achievedLevel,
            boolean belowLowest,
            Long achievedPriceMinor,
            String achievedPriceFormatted,
            long settlementMinor,
            String settlementFormatted,
            String method,
            String methodSetBy,
            Instant methodSetAt,
            String status,
            Instant chooseBy,
            Instant recordedAt,
            Instant settledAt,
            String settledBy,
            String refundMethod,
            String refundReference,
            long pointsRemoved,
            boolean refundAllowed,
            boolean locked,
            Proof proof) {
    }

    /**
     * Everything the admin's Champions card needs.
     *
     * @param applies whether this order is measured by a result at all
     * @param why     when it is not, why -- for the card to say instead of showing nothing
     * @param options every level from the lowest tier to the one ordered, each priced
     */
    public record AdminView(
            boolean applies,
            String why,
            String kind,
            String currency,
            Integer targetLevel,
            Integer lowestLevel,
            List<Option> options,
            long orderedPriceMinor,
            String orderedPriceFormatted,
            long servicePaidMinor,
            String servicePaidFormatted,
            long storeCreditUsedMinor,
            Recorded result) {
    }
}
