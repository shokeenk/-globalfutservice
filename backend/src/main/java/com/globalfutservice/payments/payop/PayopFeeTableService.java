package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Payop fee table: what each payment method costs, as the client's Payop pricing sheet
 * says, kept in the database rather than in code.
 *
 * <p>Filled by uploading the sheet (never committed); afterwards an admin can correct a single
 * method by hand, or add one the sheet does not list -- a card method, say. Every change,
 * imported or by hand, bumps the method's version and leaves an audit row with the before and
 * after, so the fee on any invoice can be traced to the exact row it was worked out from. A
 * method added by hand is kept when the sheet is imported again, unless the sheet lists it.
 */
@Service
public class PayopFeeTableService {

    private static final Logger log = LoggerFactory.getLogger(PayopFeeTableService.class);

    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    /** Payop's own method types: cards_international, bank_transfer, ewallet... */
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{1,39}");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** A sanity ceiling on the fixed part; the sheet's largest is a few EUR. */
    private static final BigDecimal MAX_FIXED_EUR = BigDecimal.valueOf(100);

    /**
     * What an import did, for the admin who ran it. {@code keptManual}: methods added by hand
     * that the sheet does not list, left on as they were.
     */
    public record ImportResult(int methods, int added, int changed, int unchanged, int deactivated, int keptManual) {
    }

    /** An admin's correction to one method. Name, type and region stay as imported. */
    public record Edit(BigDecimal fixedEur, BigDecimal percent, List<String> countries, List<String> currencies,
                       boolean active) {
    }

    /** One method priced by hand, in full: added when the table has no such ID, else corrected. */
    public record Manual(long methodId, String name, String type, String region, BigDecimal fixedEur,
                         BigDecimal percent, List<String> countries, List<String> currencies, boolean active) {
    }

    private final PayopFeeMethodRepository methods;
    private final PayopFeeAuditRepository audit;
    private final ObjectMapper json;
    private final Clock clock;

    public PayopFeeTableService(PayopFeeMethodRepository methods, PayopFeeAuditRepository audit, ObjectMapper json,
                                Clock clock) {
        this.methods = methods;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Loads the sheet. All or nothing: a sheet with any bad row is refused whole, naming each
     * problem, and so is one whose method count is not the {@code expectedMethods} the admin
     * said it has. Methods the table holds but the sheet no longer lists are switched off.
     */
    @Transactional
    public ImportResult importSheet(byte[] xlsx, Integer expectedMethods, Long actorId) {
        List<PayopFeeSheet.Method> sheet;
        try {
            sheet = PayopFeeSheet.parse(xlsx);
        } catch (PayopFeeSheet.SheetException e) {
            throw new ApiExceptions.BadRequestException(e.getMessage());
        }
        if (expectedMethods != null && sheet.size() != expectedMethods) {
            throw new ApiExceptions.BadRequestException("The sheet has " + sheet.size() + " payment methods, not the "
                    + expectedMethods + " expected. Nothing was imported.");
        }
        Instant now = clock.instant();
        Map<Long, PayopFeeMethodEntity> held = methods.findAll().stream()
                .collect(Collectors.toMap(PayopFeeMethodEntity::getMethodId, Function.identity()));
        int added = 0;
        int changed = 0;
        int unchanged = 0;
        for (PayopFeeSheet.Method m : sheet) {
            PayopFeeMethodEntity row = held.remove(m.methodId());
            if (row == null) {
                row = methods.save(new PayopFeeMethodEntity(m.methodId(), m.name(), m.type(), m.region(),
                        m.fixedEur(), m.percent(), m.countries(), m.currencies(), actorId, now));
                record(row, PayopFeeAuditEntity.IMPORTED, null, actorId, now);
                added++;
            } else if (row.isActive() && !row.isManual() && same(row, m)) {
                unchanged++;
            } else {
                String before = snapshot(row);
                row.change(m.name(), m.type(), m.region(), m.fixedEur(), m.percent(), m.countries(), m.currencies(),
                        true, actorId, now);
                row.takenBySheet();
                record(row, PayopFeeAuditEntity.IMPORTED, before, actorId, now);
                changed++;
            }
        }
        int deactivated = 0;
        int keptManual = 0;
        for (PayopFeeMethodEntity gone : held.values()) {
            if (gone.isManual()) {
                // Priced by hand because the sheet does not have it: still not in the sheet is no news.
                keptManual++;
            } else if (gone.isActive()) {
                String before = snapshot(gone);
                gone.change(gone.getName(), gone.getMethodType(), gone.getRegion(), gone.getFixedEur(),
                        gone.getPercent(), gone.countryList(), gone.currencyList(), false, actorId, now);
                record(gone, PayopFeeAuditEntity.IMPORTED, before, actorId, now);
                deactivated++;
            }
        }
        log.info("Payop fee table imported: {} methods, {} added, {} changed, {} unchanged, {} switched off, "
                        + "{} priced by hand kept", sheet.size(), added, changed, unchanged, deactivated, keptManual);
        return new ImportResult(sheet.size(), added, changed, unchanged, deactivated, keptManual);
    }

    /**
     * One method priced by hand: added -- marked MANUAL, so a later import of the sheet keeps
     * it -- or, for an ID the table has, corrected in full, name and type included. The same
     * checks as the import and the edit, versioned and audited the same way.
     */
    @Transactional
    public PayopFeeMethodEntity saveManual(Manual m, Long actorId) {
        if (m.methodId() <= 0) {
            throw new ApiExceptions.BadRequestException("The method ID is Payop's number for the method: a positive "
                    + "whole number");
        }
        String name = m.name() == null ? "" : m.name().trim();
        if (name.isEmpty() || name.length() > 120) {
            throw new ApiExceptions.BadRequestException("A method needs a name, up to 120 characters");
        }
        String type = m.type() == null ? "" : m.type().trim().toLowerCase(Locale.ROOT);
        if (!TYPE.matcher(type).matches()) {
            throw new ApiExceptions.BadRequestException("The type is Payop's, e.g. cards_international or "
                    + "bank_transfer: lower-case letters, digits and underscores");
        }
        String region = m.region() == null || m.region().isBlank() ? null : m.region().trim();
        BigDecimal fixed = fixedEur(m.fixedEur());
        BigDecimal percent = percent(m.percent());
        List<String> countries = countries(m.countries());
        List<String> currencies = currencies(m.currencies());
        Instant now = clock.instant();
        java.util.Optional<PayopFeeMethodEntity> held = methods.findById(m.methodId());
        if (held.isEmpty()) {
            PayopFeeMethodEntity row = methods.save(PayopFeeMethodEntity.manual(m.methodId(), name, type, region, fixed,
                    percent, countries, currencies, m.active(), actorId, now));
            record(row, PayopFeeAuditEntity.ADDED, null, actorId, now);
            log.info("Payop method {} ({}) priced by hand by account {}", m.methodId(), type, actorId);
            return row;
        }
        PayopFeeMethodEntity row = held.get();
        String before = snapshot(row);
        row.change(name, type, region, fixed, percent, countries, currencies, m.active(), actorId, now);
        record(row, PayopFeeAuditEntity.UPDATED, before, actorId, now);
        log.info("Payop method {} corrected by hand by account {}: now version {}", m.methodId(), actorId,
                row.getVersion());
        return row;
    }

    /** An admin's correction to one method: checked, versioned and audited. */
    @Transactional
    public PayopFeeMethodEntity update(long methodId, Edit edit, Long actorId) {
        PayopFeeMethodEntity row = methods.findById(methodId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No Payop method " + methodId + " in the fee table"));
        BigDecimal fixed = fixedEur(edit.fixedEur());
        BigDecimal percent = percent(edit.percent());
        List<String> countries = countries(edit.countries());
        List<String> currencies = currencies(edit.currencies());
        String before = snapshot(row);
        row.change(row.getName(), row.getMethodType(), row.getRegion(), fixed, percent, countries, currencies,
                edit.active(), actorId, clock.instant());
        record(row, PayopFeeAuditEntity.UPDATED, before, actorId, clock.instant());
        log.info("Payop fee for method {} changed by account {}: now version {}", methodId, actorId, row.getVersion());
        return row;
    }

    @Transactional(readOnly = true)
    public List<PayopFeeMethodEntity> list() {
        return methods.findAllByOrderByRegionAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<PayopFeeAuditEntity> history(Long methodId) {
        return methodId == null ? audit.findAllByOrderByAtDescIdDesc(PageRequest.of(0, 200))
                : audit.findByMethodIdOrderByAtDescIdDesc(methodId);
    }

    /* ------------------------------------------------------------------- helpers --- */

    private static boolean same(PayopFeeMethodEntity row, PayopFeeSheet.Method m) {
        return row.getName().equals(m.name()) && row.getMethodType().equals(m.type())
                && java.util.Objects.equals(row.getRegion(), m.region())
                && row.getFixedEur().compareTo(m.fixedEur()) == 0 && row.getPercent().compareTo(m.percent()) == 0
                && row.countryList().equals(m.countries()) && row.currencyList().equals(m.currencies());
    }

    private static BigDecimal fixedEur(BigDecimal fixed) {
        if (fixed == null || fixed.signum() < 0 || fixed.compareTo(MAX_FIXED_EUR) > 0
                || fixed.stripTrailingZeros().scale() > 2) {
            throw new ApiExceptions.BadRequestException("The fixed fee must be between 0 and 100 EUR, to the cent");
        }
        return fixed;
    }

    private static BigDecimal percent(BigDecimal percent) {
        if (percent == null || percent.signum() < 0 || percent.compareTo(HUNDRED) >= 0
                || percent.stripTrailingZeros().scale() > 3) {
            throw new ApiExceptions.BadRequestException("The percentage must be at least 0 and under 100, to 3 decimals");
        }
        return percent;
    }

    private static List<String> countries(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String c : in == null ? List.<String>of() : in) {
            String code = c == null ? "" : c.trim().toUpperCase(Locale.ROOT);
            if (code.equals(CountryNames.EVERYWHERE)) {
                return List.of(CountryNames.EVERYWHERE);
            }
            if (!ISO_COUNTRIES.contains(code)) {
                throw new ApiExceptions.BadRequestException("'" + c + "' is not a two-letter country code");
            }
            if (!out.contains(code)) {
                out.add(code);
            }
        }
        if (out.isEmpty()) {
            throw new ApiExceptions.BadRequestException("A method needs at least one country, or * for all");
        }
        return out;
    }

    private static List<String> currencies(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String c : in == null ? List.<String>of() : in) {
            String code = c == null ? "" : c.trim().toUpperCase(Locale.ROOT);
            if (!CURRENCY.matcher(code).matches()) {
                throw new ApiExceptions.BadRequestException("'" + c + "' is not a three-letter currency code");
            }
            if (!out.contains(code)) {
                out.add(code);
            }
        }
        if (out.isEmpty()) {
            throw new ApiExceptions.BadRequestException("A method needs at least one processing currency");
        }
        return out;
    }

    private void record(PayopFeeMethodEntity row, String action, String before, Long actorId, Instant at) {
        audit.save(new PayopFeeAuditEntity(row.getMethodId(), row.getVersion(), action, before, snapshot(row),
                actorId, at));
    }

    private String snapshot(PayopFeeMethodEntity row) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", row.getName());
        s.put("type", row.getMethodType());
        s.put("region", row.getRegion());
        s.put("fixedEur", row.getFixedEur().toPlainString());
        s.put("percent", row.getPercent().toPlainString());
        s.put("countries", row.countryList());
        s.put("currencies", row.currencyList());
        s.put("active", row.isActive());
        s.put("source", row.getSource());
        s.put("version", row.getVersion());
        try {
            return json.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not record the fee table change", e);
        }
    }
}
