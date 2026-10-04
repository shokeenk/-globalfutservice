package com.globalfutservice.payments.payop;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads Payop's pricing sheet: the "checkout" sheet of the .xlsx Payop sends the client.
 *
 * <p>The sheet's shape, and what is done about it:
 * <ul>
 *   <li>Columns A-F are the table: ID, Payment method, Payment method type, Fee per
 *       Transaction, Countries, Processing Currencies. Anything to the right is ignored --
 *       the Latin America rows repeat themselves across a dozen more columns.</li>
 *   <li>Region headings (International, Europe, ...) are rows of their own; the region is
 *       kept on each method beneath it.</li>
 *   <li>IDs are stored as floats ({@code 3.0000018E7}) and converted exactly (30000018);
 *       one that is not a whole number is an error, not a rounding.</li>
 *   <li>Fees read "0.30 EUR + 4.0%": a fixed part in EUR plus a percentage.</li>
 *   <li>Countries are English names, mapped by {@link CountryNames}; "International" means
 *       every country.</li>
 *   <li>Names are trimmed (one carries a leading space); symbols such as ® are kept.</li>
 * </ul>
 *
 * <p>Nothing is skipped quietly. Every row that is not a heading, a region or a method that
 * parses is a problem, and {@link SheetException} lists all of them at once.
 *
 * <p>An uploaded file is untrusted: the XML parser refuses DOCTYPEs and external entities,
 * and no part of the archive may unpack beyond {@link #MAX_PART_BYTES}.
 */
public final class PayopFeeSheet {

    static final String SHEET = "checkout";
    static final int MAX_PART_BYTES = 16 * 1024 * 1024;
    private static final Set<String> REGIONS =
            Set.of("International", "Europe", "Northern America", "Latin America", "Africa", "Asia");
    private static final Set<String> HEADINGS = Set.of("ID", "Pricing offer");
    private static final Pattern FEE =
            Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*EUR\\s*\\+\\s*(\\d+(?:\\.\\d+)?)\\s*%\\s*$");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private PayopFeeSheet() {
    }

    /** One payment method, as the sheet prices it. */
    public record Method(long methodId, String name, String type, String region, BigDecimal fixedEur,
                         BigDecimal percent, List<String> countries, List<String> currencies, int row) {
    }

    /** The sheet could not be read, or some rows did not parse; {@code problems} names each. */
    public static final class SheetException extends RuntimeException {
        private final List<String> problems;

        SheetException(List<String> problems) {
            super(problems.size() + " problem(s) in the Payop pricing sheet: " + String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    public static List<Method> parse(byte[] xlsx) {
        Map<String, byte[]> parts = unzip(xlsx);
        List<String> shared = sharedStrings(parts.get("xl/sharedStrings.xml"));
        Document sheet = xml(parts.get(sheetPath(parts)), "the checkout sheet");

        List<Method> methods = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        String region = null;
        NodeList rows = sheet.getElementsByTagNameNS(MAIN, "row");
        for (int i = 0; i < rows.getLength(); i++) {
            Element row = (Element) rows.item(i);
            int rowNumber = Integer.parseInt(row.getAttribute("r"));
            Map<Integer, String> cells = cells(row, shared);
            String a = trim(cells.get(1));
            String b = trim(cells.get(2));
            if (a.isEmpty() && b.isEmpty()) {
                continue;
            }
            if (HEADINGS.contains(a)) {
                continue;
            }
            if (REGIONS.contains(a) && b.isEmpty()) {
                region = a;
                continue;
            }
            try {
                Method m = method(rowNumber, region, a, b, trim(cells.get(3)), trim(cells.get(4)),
                        trim(cells.get(5)), trim(cells.get(6)));
                if (!seen.add(m.methodId())) {
                    throw new IllegalArgumentException("ID " + m.methodId() + " appears twice");
                }
                methods.add(m);
            } catch (IllegalArgumentException e) {
                problems.add("row " + rowNumber + ": " + e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new SheetException(problems);
        }
        if (methods.isEmpty()) {
            throw new SheetException(List.of("no payment methods found on the '" + SHEET + "' sheet"));
        }
        return methods;
    }

    private static Method method(int row, String region, String id, String name, String type, String fee,
                                 String countries, String currencies) {
        long methodId;
        try {
            BigDecimal n = new BigDecimal(id);
            if (n.signum() <= 0 || n.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("ID '" + id + "' is not a whole positive number");
            }
            methodId = n.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("ID '" + id + "' is not a number");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("no payment method name");
        }
        if (type.isEmpty()) {
            throw new IllegalArgumentException("no payment method type");
        }
        Matcher f = FEE.matcher(fee);
        if (!f.matches()) {
            throw new IllegalArgumentException("fee '" + fee + "' is not 'X EUR + Y%'");
        }
        BigDecimal percent = new BigDecimal(f.group(2));
        if (percent.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new IllegalArgumentException("fee percentage " + percent + " is 100% or more");
        }
        List<String> isoCountries = new ArrayList<>();
        for (String country : split(countries)) {
            Optional<String> iso = CountryNames.iso(country);
            if (iso.isEmpty()) {
                throw new IllegalArgumentException("unknown country '" + country + "'");
            }
            isoCountries.add(iso.get());
        }
        if (isoCountries.isEmpty()) {
            throw new IllegalArgumentException("no countries");
        }
        if (isoCountries.contains(CountryNames.EVERYWHERE)) {
            isoCountries = List.of(CountryNames.EVERYWHERE);
        }
        List<String> currencyCodes = new ArrayList<>();
        for (String currency : split(currencies)) {
            String code = currency.toUpperCase(Locale.ROOT);
            if (!CURRENCY.matcher(code).matches()) {
                throw new IllegalArgumentException("currency '" + currency + "' is not a three-letter code");
            }
            currencyCodes.add(code);
        }
        if (currencyCodes.isEmpty()) {
            throw new IllegalArgumentException("no processing currencies");
        }
        return new Method(methodId, name, type, region, new BigDecimal(f.group(1)), percent,
                List.copyOf(isoCountries), List.copyOf(currencyCodes), row);
    }

    /* --------------------------------------------------------------- xlsx --- */

    private static Map<String, byte[]> unzip(byte[] xlsx) {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    parts.put(entry.getName().replace('\\', '/'), readCapped(zip, entry.getName()));
                }
            }
        } catch (IOException e) {
            throw new SheetException(List.of("not a readable .xlsx file"));
        }
        if (!parts.containsKey("xl/workbook.xml")) {
            throw new SheetException(List.of("not an .xlsx workbook"));
        }
        return parts;
    }

    private static byte[] readCapped(InputStream in, String name) throws IOException {
        byte[] data = in.readNBytes(MAX_PART_BYTES + 1);
        if (data.length > MAX_PART_BYTES) {
            throw new SheetException(List.of("part '" + name + "' unpacks beyond " + MAX_PART_BYTES + " bytes"));
        }
        return data;
    }

    private static String sheetPath(Map<String, byte[]> parts) {
        Document workbook = xml(parts.get("xl/workbook.xml"), "the workbook");
        NodeList sheets = workbook.getElementsByTagNameNS(MAIN, "sheet");
        String relId = null;
        for (int i = 0; i < sheets.getLength(); i++) {
            Element s = (Element) sheets.item(i);
            if (SHEET.equals(s.getAttribute("name"))) {
                relId = s.getAttributeNS(RELS, "id");
            }
        }
        if (relId == null) {
            throw new SheetException(List.of("no '" + SHEET + "' sheet in the workbook"));
        }
        Document rels = xml(parts.get("xl/_rels/workbook.xml.rels"), "the workbook's relationships");
        NodeList relationships = rels.getElementsByTagName("Relationship");
        for (int i = 0; i < relationships.getLength(); i++) {
            Element r = (Element) relationships.item(i);
            if (relId.equals(r.getAttribute("Id"))) {
                String target = r.getAttribute("Target");
                String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                if (!parts.containsKey(path)) {
                    throw new SheetException(List.of("the '" + SHEET + "' sheet's data is missing"));
                }
                return path;
            }
        }
        throw new SheetException(List.of("the '" + SHEET + "' sheet's data is missing"));
    }

    private static List<String> sharedStrings(byte[] data) {
        List<String> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        NodeList items = xml(data, "the shared strings").getElementsByTagNameNS(MAIN, "si");
        for (int i = 0; i < items.getLength(); i++) {
            out.add(text((Element) items.item(i)));
        }
        return out;
    }

    /** Columns 1-6 only, by their letters; everything to the right of F is ignored. */
    private static Map<Integer, String> cells(Element row, List<String> shared) {
        Map<Integer, String> out = new LinkedHashMap<>();
        NodeList cells = row.getElementsByTagNameNS(MAIN, "c");
        for (int i = 0; i < cells.getLength(); i++) {
            Element c = (Element) cells.item(i);
            int column = column(c.getAttribute("r"));
            if (column < 1 || column > 6) {
                continue;
            }
            String type = c.getAttribute("t");
            String value;
            if ("inlineStr".equals(type)) {
                value = text(c);
            } else {
                NodeList v = c.getElementsByTagNameNS(MAIN, "v");
                String raw = v.getLength() == 0 ? null : v.item(0).getTextContent();
                if (raw == null) {
                    value = null;
                } else if ("s".equals(type)) {
                    int index = Integer.parseInt(raw.trim());
                    value = index >= 0 && index < shared.size() ? shared.get(index) : null;
                } else {
                    value = raw;
                }
            }
            out.put(column, value);
        }
        return out;
    }

    private static int column(String reference) {
        int n = 0;
        for (char ch : reference.toCharArray()) {
            if (ch < 'A' || ch > 'Z') {
                break;
            }
            n = n * 26 + (ch - 'A' + 1);
        }
        return n;
    }

    private static String text(Element e) {
        StringBuilder b = new StringBuilder();
        NodeList ts = e.getElementsByTagNameNS(MAIN, "t");
        for (int i = 0; i < ts.getLength(); i++) {
            b.append(ts.item(i).getTextContent());
        }
        return b.toString();
    }

    private static Document xml(byte[] data, String what) {
        if (data == null) {
            throw new SheetException(List.of(what + " is missing from the file"));
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder builder = f.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(data));
        } catch (Exception e) {
            throw new SheetException(List.of(what + " could not be read"));
        }
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
