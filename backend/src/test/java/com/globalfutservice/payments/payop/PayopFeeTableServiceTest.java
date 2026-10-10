package com.globalfutservice.payments.payop;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayopFeeTableServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);
    private static final List<Object> HEADER = row("ID", "Payment method", "Payment method type",
            "Fee per Transaction", "Countries", "Processing Currencies");

    private final Map<Long, PayopFeeMethodEntity> table = new LinkedHashMap<>();
    private final List<PayopFeeAuditEntity> audit = new ArrayList<>();
    private PayopFeeTableService service;

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    private static byte[] sheet(List<Object>... methods) throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(HEADER);
        rows.add(row("Europe"));
        rows.addAll(List.of(methods));
        return PayopFeeSheetTest.xlsx(rows);
    }

    @BeforeEach
    void setUp() {
        PayopFeeMethodRepository methods = mock(PayopFeeMethodRepository.class);
        when(methods.findAll()).thenAnswer(inv -> new ArrayList<>(table.values()));
        when(methods.save(any(PayopFeeMethodEntity.class))).thenAnswer(inv -> {
            PayopFeeMethodEntity e = inv.getArgument(0);
            table.put(e.getMethodId(), e);
            return e;
        });
        when(methods.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(table.get((Long) inv.getArgument(0))));
        PayopFeeAuditRepository audits = mock(PayopFeeAuditRepository.class);
        when(audits.save(any(PayopFeeAuditEntity.class))).thenAnswer(inv -> {
            audit.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        service = new PayopFeeTableService(methods, audits, MAPPER, NOW);
    }

    @Test
    @DisplayName("a first import adds every method, at version 1, each with an audit row")
    void firstImport() throws Exception {
        PayopFeeTableService.ImportResult r = service.importSheet(sheet(
                row(1, "Pay by bank", "bank_transfer", "0.30 EUR + 2.4%", "Austria, Germany", "EUR"),
                row(2, "Wallet", "ewallet", "0.45 EUR + 4.7%", "Canada", "CAD")), 2, 9L);

        assertThat(r).isEqualTo(new PayopFeeTableService.ImportResult(2, 2, 0, 0, 0, 0));
        PayopFeeMethodEntity bank = table.get(1L);
        assertThat(bank.getVersion()).isEqualTo(1);
        assertThat(bank.getFixedEur()).isEqualByComparingTo("0.30");
        assertThat(bank.getPercent()).isEqualByComparingTo("2.4");
        assertThat(bank.countryList()).containsExactly("AT", "DE");
        assertThat(bank.getUpdatedBy()).isEqualTo(9L);
        assertThat(audit).hasSize(2).allSatisfy(a -> {
            assertThat(a.getAction()).isEqualTo(PayopFeeAuditEntity.IMPORTED);
            assertThat(a.getBeforeJson()).isNull();
            assertThat(a.getActorId()).isEqualTo(9L);
        });
        JsonNode after = MAPPER.readTree(audit.get(0).getAfterJson());
        assertThat(after.path("percent").asText()).isEqualTo("2.4");
        assertThat(after.path("countries").toString()).isEqualTo("[\"AT\",\"DE\"]");
    }

    @Test
    @DisplayName("a re-import changes only what changed, and switches off methods the sheet dropped")
    void reimport() throws Exception {
        service.importSheet(sheet(
                row(1, "Pay by bank", "bank_transfer", "0.30 EUR + 2.4%", "Austria", "EUR"),
                row(2, "Wallet", "ewallet", "0.45 EUR + 4.7%", "Canada", "CAD"),
                row(3, "Cash", "cash", "1.50 EUR + 3.5%", "Colombia", "COP")), null, 9L);
        audit.clear();

        PayopFeeTableService.ImportResult r = service.importSheet(sheet(
                row(1, "Pay by bank", "bank_transfer", "0.30 EUR + 2.4%", "Austria", "EUR"),
                row(2, "Wallet", "ewallet", "0.45 EUR + 5.0%", "Canada", "CAD")), 2, 10L);

        assertThat(r).isEqualTo(new PayopFeeTableService.ImportResult(2, 0, 1, 1, 1, 0));
        assertThat(table.get(1L).getVersion()).isEqualTo(1);
        assertThat(table.get(2L).getVersion()).isEqualTo(2);
        assertThat(table.get(2L).getPercent()).isEqualByComparingTo("5.0");
        assertThat(table.get(3L).isActive()).isFalse();
        assertThat(table.get(3L).getVersion()).isEqualTo(2);
        assertThat(audit).extracting(PayopFeeAuditEntity::getMethodId).containsExactlyInAnyOrder(2L, 3L);
        PayopFeeAuditEntity wallet = audit.stream().filter(a -> a.getMethodId() == 2L).findFirst().orElseThrow();
        assertThat(MAPPER.readTree(wallet.getBeforeJson()).path("percent").asText()).isEqualTo("4.7");
        assertThat(MAPPER.readTree(wallet.getAfterJson()).path("percent").asText()).isEqualTo("5.0");
        assertThat(wallet.getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("a sheet with a bad row, or the wrong number of methods, imports nothing")
    void allOrNothing() throws Exception {
        assertThatThrownBy(() -> service.importSheet(sheet(
                row(1, "Good", "ewallet", "0.30 EUR + 4.0%", "Austria", "EUR"),
                row(2, "Bad", "ewallet", "4% + 0.30", "Austria", "EUR")), null, 9L))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("row 4: fee '4% + 0.30' is not 'X EUR + Y%'");
        assertThatThrownBy(() -> service.importSheet(sheet(
                row(1, "Good", "ewallet", "0.30 EUR + 4.0%", "Austria", "EUR")), 104, 9L))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessage("The sheet has 1 payment methods, not the 104 expected. Nothing was imported.");
        assertThat(table).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("an admin edit is checked, bumps the version, and is audited with before and after")
    void edit() throws Exception {
        service.importSheet(sheet(row(1, "Pay by bank", "bank_transfer", "0.30 EUR + 2.4%", "Austria", "EUR")),
                null, 9L);
        audit.clear();

        PayopFeeMethodEntity row = service.update(1L, new PayopFeeTableService.Edit(new BigDecimal("0.35"),
                new BigDecimal("2.5"), List.of("at", "de", "AT"), List.of("eur"), true), 11L);

        assertThat(row.getVersion()).isEqualTo(2);
        assertThat(row.countryList()).containsExactly("AT", "DE");
        assertThat(row.currencyList()).containsExactly("EUR");
        assertThat(row.getUpdatedBy()).isEqualTo(11L);
        assertThat(audit).singleElement().satisfies(a -> {
            assertThat(a.getAction()).isEqualTo(PayopFeeAuditEntity.UPDATED);
            assertThat(a.getActorId()).isEqualTo(11L);
            assertThat(MAPPER.readTree(a.getBeforeJson()).path("fixedEur").asText()).isEqualTo("0.30");
            assertThat(MAPPER.readTree(a.getAfterJson()).path("fixedEur").asText()).isEqualTo("0.35");
        });

        service.update(1L, new PayopFeeTableService.Edit(new BigDecimal("0.35"), new BigDecimal("2.5"),
                List.of("DE", "*"), List.of("EUR"), false), 11L);
        assertThat(table.get(1L).countryList()).containsExactly("*");
        assertThat(table.get(1L).isActive()).isFalse();
    }

    @Test
    @DisplayName("an edit with an impossible fee, country or currency is refused and changes nothing")
    void badEdits() throws Exception {
        service.importSheet(sheet(row(1, "Pay by bank", "bank_transfer", "0.30 EUR + 2.4%", "Austria", "EUR")),
                null, 9L);
        audit.clear();
        BigDecimal ok = new BigDecimal("0.30");
        BigDecimal pct = new BigDecimal("2.4");
        List<PayopFeeTableService.Edit> bad = List.of(
                new PayopFeeTableService.Edit(new BigDecimal("-0.01"), pct, List.of("AT"), List.of("EUR"), true),
                new PayopFeeTableService.Edit(new BigDecimal("0.305"), pct, List.of("AT"), List.of("EUR"), true),
                new PayopFeeTableService.Edit(ok, new BigDecimal("100"), List.of("AT"), List.of("EUR"), true),
                new PayopFeeTableService.Edit(ok, new BigDecimal("2.4001"), List.of("AT"), List.of("EUR"), true),
                new PayopFeeTableService.Edit(ok, pct, List.of("Austria"), List.of("EUR"), true),
                new PayopFeeTableService.Edit(ok, pct, List.of(), List.of("EUR"), true),
                new PayopFeeTableService.Edit(ok, pct, List.of("AT"), List.of("Euro"), true),
                new PayopFeeTableService.Edit(ok, pct, List.of("AT"), List.of(), true));
        for (PayopFeeTableService.Edit edit : bad) {
            assertThatThrownBy(() -> service.update(1L, edit, 11L))
                    .as(edit.toString()).isInstanceOf(ApiExceptions.BadRequestException.class);
        }
        assertThatThrownBy(() -> service.update(99L, bad.get(0), 11L))
                .isInstanceOf(ApiExceptions.NotFoundException.class);
        assertThat(table.get(1L).getVersion()).isEqualTo(1);
        assertThat(audit).isEmpty();
    }

    /* ------------------------------------------------------------ priced by hand --- */

    private static PayopFeeTableService.Manual card(String fixed, String percent, List<String> countries) {
        return new PayopFeeTableService.Manual(900001L, "Visa / Mastercard", "cards_international", null,
                new BigDecimal(fixed), new BigDecimal(percent), countries, List.of("INR", "USD"), true);
    }

    @Test
    @DisplayName("a method the sheet does not list is added by hand: checked, marked manual, audited as ADDED")
    void addedByHand() throws Exception {
        PayopFeeMethodEntity row = service.saveManual(card("0.20", "3.5", List.of("in")), 9L);

        assertThat(row.isManual()).isTrue();
        assertThat(row.getVersion()).isEqualTo(1);
        assertThat(row.getMethodType()).isEqualTo("cards_international");
        assertThat(row.countryList()).containsExactly("IN");
        assertThat(row.isActive()).isTrue();
        assertThat(table).containsKey(900001L);
        assertThat(audit).singleElement().satisfies(a -> {
            assertThat(a.getAction()).isEqualTo(PayopFeeAuditEntity.ADDED);
            assertThat(a.getBeforeJson()).isNull();
            assertThat(a.getActorId()).isEqualTo(9L);
        });
        JsonNode after = MAPPER.readTree(audit.get(0).getAfterJson());
        assertThat(after.path("source").asText()).isEqualTo("MANUAL");
        assertThat(after.path("percent").asText()).isEqualTo("3.5");
    }

    @Test
    @DisplayName("by hand, a method already in the table is corrected in full -- name and type too -- as a new version")
    void correctedByHand() throws Exception {
        service.saveManual(card("0.20", "3.5", List.of("IN")), 9L);
        audit.clear();

        PayopFeeMethodEntity row = service.saveManual(new PayopFeeTableService.Manual(900001L, "Cards", "cards_local",
                "India", new BigDecimal("0.10"), new BigDecimal("2.9"), List.of("IN"), List.of("INR"), true), 10L);

        assertThat(row.getVersion()).isEqualTo(2);
        assertThat(row.getName()).isEqualTo("Cards");
        assertThat(row.getMethodType()).isEqualTo("cards_local");
        assertThat(audit).singleElement().satisfies(a -> {
            assertThat(a.getAction()).isEqualTo(PayopFeeAuditEntity.UPDATED);
            assertThat(a.getBeforeJson()).contains("\"percent\":\"3.5\"");
            assertThat(a.getActorId()).isEqualTo(10L);
        });
    }

    @Test
    @DisplayName("by hand, the import's checks: a bad ID, name, type, fee, country or currency is refused, nothing saved")
    void manualChecks() {
        BigDecimal fixed = new BigDecimal("0.20");
        BigDecimal pct = new BigDecimal("3.5");
        List<PayopFeeTableService.Manual> bad = List.of(
                new PayopFeeTableService.Manual(0L, "Card", "cards", null, fixed, pct, List.of("IN"), List.of("INR"), true),
                new PayopFeeTableService.Manual(1L, " ", "cards", null, fixed, pct, List.of("IN"), List.of("INR"), true),
                new PayopFeeTableService.Manual(1L, "Card", "Cards!", null, fixed, pct, List.of("IN"), List.of("INR"), true),
                new PayopFeeTableService.Manual(1L, "Card", "cards", null, new BigDecimal("-1"), pct, List.of("IN"),
                        List.of("INR"), true),
                new PayopFeeTableService.Manual(1L, "Card", "cards", null, fixed, new BigDecimal("100"), List.of("IN"),
                        List.of("INR"), true),
                new PayopFeeTableService.Manual(1L, "Card", "cards", null, fixed, pct, List.of("India"), List.of("INR"), true),
                new PayopFeeTableService.Manual(1L, "Card", "cards", null, fixed, pct, List.of("IN"), List.of(), true));
        for (PayopFeeTableService.Manual m : bad) {
            assertThatThrownBy(() -> service.saveManual(m, 9L)).isInstanceOf(ApiExceptions.BadRequestException.class);
        }
        assertThat(table).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("importing the sheet again keeps a method priced by hand; a sheet that lists it takes it over")
    void importKeepsManual() throws Exception {
        service.importSheet(sheet(row(1, "Pay by bank", "bank_transfer", "0.30 EUR + 2.4%", "Austria", "EUR")), null, 9L);
        service.saveManual(card("0.20", "3.5", List.of("IN")), 9L);

        PayopFeeTableService.ImportResult r = service.importSheet(
                sheet(row(1, "Pay by bank", "bank_transfer", "0.30 EUR + 2.4%", "Austria", "EUR")), null, 10L);
        assertThat(r).isEqualTo(new PayopFeeTableService.ImportResult(1, 0, 0, 1, 0, 1));
        assertThat(table.get(900001L).isActive()).isTrue();
        assertThat(table.get(900001L).isManual()).isTrue();

        service.importSheet(sheet(row(1, "Pay by bank", "bank_transfer", "0.30 EUR + 2.4%", "Austria", "EUR"),
                row(900001, "Cards", "cards_international", "0.25 EUR + 3.9%", "India", "INR")), null, 10L);
        assertThat(table.get(900001L).isManual()).isFalse();
        assertThat(table.get(900001L).getPercent()).isEqualByComparingTo("3.9");
    }
}
