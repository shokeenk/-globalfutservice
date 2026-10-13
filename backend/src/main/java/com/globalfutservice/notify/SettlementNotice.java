package com.globalfutservice.notify;

import java.time.Instant;

/**
 * What a customer's Champions emails say about the result: the wins, the amount and how it
 * is settled. Built from the result alone -- never from the order's history, so an admin's
 * staff-only note cannot reach an email.
 *
 * @param kind                the result's kind, CHAMPS_WINS
 * @param settlementFormatted what is owed, in the order's currency; null when nothing is
 * @param method              REFUND, STORE_CREDIT, or null while nobody has chosen
 * @param status              NONE, PENDING, PAID, ISSUED or CANCELLED
 * @param chooseBy            until when the customer can choose; null once they cannot
 * @param automatic           settled by the default after the choice window closed
 * @param defaultIsStoreCredit what the customer gets when they do not choose
 */
public record SettlementNotice(
        String kind,
        int targetLevel,
        int achievedLevel,
        boolean belowLowest,
        String settlementFormatted,
        String method,
        String status,
        Instant chooseBy,
        boolean automatic,
        boolean defaultIsStoreCredit) {

    public boolean allAchieved() {
        return achievedLevel >= targetLevel;
    }
}
