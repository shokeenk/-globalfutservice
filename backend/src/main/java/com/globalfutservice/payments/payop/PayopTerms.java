package com.globalfutservice.payments.payop;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.pricing.LineCode;
import com.globalfutservice.orders.web.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Payop payment's terms, as they are written onto its order: the chosen method's own fee in
 * place of the 2.5% card fee, never beside it.
 *
 * <p>{@link #snapshot} is what the order carries from the moment an invoice is opened for it --
 * it then reads as the Payop payment it is about to be, for as long as the invoice can be paid
 * ({@code payableUntil}) -- and again once one is paid. {@link #paidBreakdown} is the order's
 * frozen breakdown once paid: the card fee line gone, the method's fee line in its place, and
 * the total the one charged.
 */
final class PayopTerms {

    private static final Logger log = LoggerFactory.getLogger(PayopTerms.class);

    private PayopTerms() {
    }

    /** The fee, the method and the invoice behind it, as the order keeps them. */
    static String snapshot(PayopInvoiceEntity a, ObjectMapper mapper) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("provider", PayopCallbackService.PROVIDER);
        s.put("label", OrderMapper.paymentFeeLabel(a.getMethodName()));
        s.put("methodId", a.getMethodId());
        s.put("methodName", a.getMethodName());
        s.put("methodVersion", a.getMethodVersion());
        s.put("fixedEur", a.getFixedEur().toPlainString());
        s.put("percent", a.getPercent().toPlainString());
        s.put("fxRate", a.getFxRate().toPlainString());
        s.put("fxSource", a.getFxSource());
        s.put("fxDate", a.getFxDate().toString());
        s.put("currency", a.getCurrency().name());
        s.put("netMinor", a.getNetMinor());
        s.put("feeMinor", a.getFeeMinor());
        s.put("totalMinor", a.getTotalMinor());
        s.put("invoiceId", a.getInvoiceId());
        s.put("payableUntil", a.getExpiresAt().toString());
        try {
            return mapper.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not record the Payop fee", e);
        }
    }

    /**
     * {@code breakdownJson} -- the quote the order was placed at -- as paid through {@code a}:
     * every line but the card fee, then the method's fee, and the total charged. Left as it is
     * if it cannot be read: the payment is applied either way, and the order's lines still show
     * the method's fee from {@link #snapshot}.
     */
    static String paidBreakdown(String breakdownJson, PayopInvoiceEntity a, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(breakdownJson);
            if (!(root instanceof ObjectNode quote)) {
                return breakdownJson;
            }
            ArrayNode lines = mapper.createArrayNode();
            for (JsonNode line : quote.path("lines")) {
                String code = line.path("code").asText();
                if (!LineCode.GATEWAY_FEE.name().equals(code) && !OrderMapper.PAYMENT_FEE.equals(code)) {
                    lines.add(line);
                }
            }
            ObjectNode fee = lines.addObject();
            fee.put("code", OrderMapper.PAYMENT_FEE);
            fee.put("label", OrderMapper.paymentFeeLabel(a.getMethodName()));
            fee.put("amountMinor", a.getFeeMinor());
            fee.put("amountFormatted", Money.ofMinor(a.getFeeMinor(), a.getCurrency()).format());
            fee.put("method", a.getMethodName());
            quote.set("lines", lines);
            quote.put("totalMinor", a.getTotalMinor());
            quote.put("totalFormatted", Money.ofMinor(a.getTotalMinor(), a.getCurrency()).format());
            return mapper.writeValueAsString(quote);
        } catch (Exception e) {
            log.warn("Could not rewrite the price breakdown for Payop invoice {}", a.getInvoiceId());
            return breakdownJson;
        }
    }
}
