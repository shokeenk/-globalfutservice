package com.globalfutservice.payments.payop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.globalfutservice.payments.payop.PayopFeeSheet.Method;
import com.globalfutservice.payments.payop.PayopFeeSheet.SheetException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * The client's own sheet is never committed (this repository is public), so these build a
 * small .xlsx with the same shape and quirks. The last test reads the real sheet when it is
 * on this machine, and is skipped where it is not.
 */
class PayopFeeSheetTest {

    /** A row of cells A, B, C...: a String is a shared string, a Number a numeric cell. */
    private static List<Object> row(Object... cells) {
        return java.util.Arrays.asList(cells);
    }

    /** A minimal .xlsx with a "checkout" sheet holding these rows. */
    static byte[] xlsx(List<List<Object>> rows) throws IOException {
        List<String> shared = new ArrayList<>();
        StringBuilder sheet = new StringBuilder(
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            List<Object> cells = rows.get(r);
            for (int c = 0; c < cells.size(); c++) {
                Object v = cells.get(c);
                if (v == null) {
                    continue;
                }
                String ref = String.valueOf((char) ('A' + c)) + (r + 1);
                if (v instanceof Number) {
                    sheet.append("<c r=\"").append(ref).append("\"><v>").append(v).append("</v></c>");
                } else {
                    shared.add((String) v);
                    sheet.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(shared.size() - 1)
                            .append("</v></c>");
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        String sst = shared.stream().map(s -> "<si><t xml:space=\"preserve\">" + s.replace("&", "&amp;")
                        .replace("<", "&lt;") + "</t></si>")
                .collect(Collectors.joining("", "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">", "</sst>"));
        Map<String, String> parts = Map.of(
                "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
                        + "<sheet name=\"checkout\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
                "xl/sharedStrings.xml", sst,
                "xl/worksheets/sheet1.xml", sheet.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> p : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(p.getKey()));
                zip.write(p.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static final List<Object> HEADER =
            row("ID", "Payment method", "Payment method type", "Fee per Transaction", "Countries", "Processing Currencies");

    @Test
    @DisplayName("reads the sheet's shape: headings, regions, float IDs, trimmed names, symbols, International")
    void shape() throws IOException {
        List<Method> methods = PayopFeeSheet.parse(xlsx(List.of(
                row("Pricing offer"),
                HEADER,
                row("International"),
                row(700001.0, "Pay Via PayDo", "ewallet", "0.30 EUR + 4.0%", "International", "EUR, AUD, CAD, GBP, USD, DKK"),
                row("Europe"),
                row("3.0000018E7", "Pay by bank", "bank_transfer", "0.30 EUR + 2.4%",
                        "Austria, Czech Republic, United Kingdom of Great Britain and Northern Ireland", "EUR"),
                row("Northern America"),
                row(210013, "Interac® via PayDo", "ewallet", "0.45 EUR + 4.7%", "Canada", "CAD"),
                row("Latin America"),
                // The Latin America rows repeat across columns G and beyond; those are ignored.
                row(6110.0, " Payvalida via PayDo", "cash", "1.50 EUR + 3.5%", "Colombia", "EUR",
                        "junk", 1, "0.99 EUR + 99%", "Mars", "XXX"))));

        assertThat(methods).extracting(Method::methodId).containsExactly(700001L, 30000018L, 210013L, 6110L);
        Method paydo = methods.get(0);
        assertThat(paydo.region()).isEqualTo("International");
        assertThat(paydo.countries()).containsExactly(CountryNames.EVERYWHERE);
        assertThat(paydo.currencies()).containsExactly("EUR", "AUD", "CAD", "GBP", "USD", "DKK");
        assertThat(paydo.fixedEur()).isEqualByComparingTo("0.30");
        assertThat(paydo.percent()).isEqualByComparingTo("4.0");
        assertThat(methods.get(1).countries()).containsExactly("AT", "CZ", "GB");
        assertThat(methods.get(1).region()).isEqualTo("Europe");
        assertThat(methods.get(2).name()).isEqualTo("Interac® via PayDo");
        assertThat(methods.get(3).name()).isEqualTo("Payvalida via PayDo");
        assertThat(methods.get(3).fixedEur()).isEqualByComparingTo("1.50");
        assertThat(methods.get(3).countries()).containsExactly("CO");
    }

    @Test
    @DisplayName("every row that does not parse is named at once, and nothing is imported")
    void failsLoudly() throws IOException {
        byte[] sheet = xlsx(List.of(
                HEADER,
                row("Europe"),
                row(1, "Good", "ewallet", "0.30 EUR + 4.0%", "Austria", "EUR"),
                row(2, "Bad fee", "ewallet", "4% + 0.30", "Austria", "EUR"),
                row(3, "Bad country", "ewallet", "0.30 EUR + 4.0%", "Atlantis", "EUR"),
                row(12.5, "Bad id", "ewallet", "0.30 EUR + 4.0%", "Austria", "EUR"),
                row(1, "Repeated id", "ewallet", "0.30 EUR + 4.0%", "Austria", "EUR"),
                row(4, "Bad currency", "ewallet", "0.30 EUR + 4.0%", "Austria", "Euro"),
                row(null, "A name with no ID", "ewallet", "0.30 EUR + 4.0%", "Austria", "EUR")));
        assertThatThrownBy(() -> PayopFeeSheet.parse(sheet))
                .isInstanceOfSatisfying(SheetException.class, e -> assertThat(e.problems()).containsExactly(
                        "row 4: fee '4% + 0.30' is not 'X EUR + Y%'",
                        "row 5: unknown country 'Atlantis'",
                        "row 6: ID '12.5' is not a whole positive number",
                        "row 7: ID 1 appears twice",
                        "row 8: currency 'Euro' is not a three-letter code",
                        "row 9: ID '' is not a number"));
    }

    @Test
    @DisplayName("refuses what is not the pricing workbook, and anything that tries XML tricks")
    void refusesOtherFiles() throws IOException {
        assertThatThrownBy(() -> PayopFeeSheet.parse("not a zip".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SheetException.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write(("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                    + "<workbook>&e;</workbook>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThatThrownBy(() -> PayopFeeSheet.parse(out.toByteArray()))
                .isInstanceOfSatisfying(SheetException.class,
                        e -> assertThat(e.problems()).containsExactly("the workbook could not be read"));
        assertThatThrownBy(() -> PayopFeeSheet.parse(xlsx(List.of(HEADER))))
                .isInstanceOfSatisfying(SheetException.class,
                        e -> assertThat(e.problems().get(0)).contains("no payment methods"));
    }

    @Test
    @DisplayName("country names: Java's English names, the sheet's aliases, International, and nothing guessed")
    void countries() {
        assertThat(CountryNames.iso("Germany")).contains("DE");
        assertThat(CountryNames.iso("  costa   rica ")).contains("CR");
        assertThat(CountryNames.iso("Czech Republic")).contains("CZ");
        assertThat(CountryNames.iso("United Kingdom of Great Britain and Northern Ireland")).contains("GB");
        assertThat(CountryNames.iso("International")).contains(CountryNames.EVERYWHERE);
        assertThat(CountryNames.iso("Atlantis")).isEmpty();
    }

    /* ------------------------------------------------------- the real sheet --- */

    static Path realSheet() {
        return Stream.of(Path.of("..", "local", "payop-pricing.xlsx"),
                        Path.of("..", "docs", "187583 Global FUT Services_pricing.xlsx"))
                .filter(Files::isRegularFile).findFirst().orElse(null);
    }

    static boolean realSheetPresent() {
        return realSheet() != null;
    }

    @Test
    @EnabledIf("realSheetPresent")
    @DisplayName("the client's sheet, when on this machine: 104 methods, every row parsed")
    void realSheetParses() throws IOException {
        List<Method> methods = PayopFeeSheet.parse(Files.readAllBytes(realSheet()));
        assertThat(methods).hasSize(104);
        assertThat(methods).extracting(Method::methodId).contains(30000018L, 700001L, 6110L);
        assertThat(methods).extracting(Method::name).contains("Interac® via PayDo", "Payvalida via PayDo");
        assertThat(methods.stream().filter(m -> m.methodId() == 30000018L).findFirst().orElseThrow().fixedEur())
                .isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(methods).allSatisfy(m -> assertThat(m.countries()).isNotEmpty());
    }
}
