package com.globalfutservice.admin;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A spreadsheet file an admin can open without it doing anything.
 *
 * <p><b>Formula injection is the reason this is not {@code String.join(",")}.</b> Names,
 * emails and payment references are typed by customers. A cell that starts with
 * {@code =}, {@code +}, {@code -} or {@code @} is a formula to Excel and Sheets, and a
 * customer who names themselves {@code =HYPERLINK(...)} gets a working link, or worse, in
 * the admin's spreadsheet. Such cells are prefixed with an apostrophe, which the
 * spreadsheet shows as nothing and reads as "this is text".
 *
 * <p>UTF-8 with a byte-order mark, because Excel otherwise opens the file as Windows-1252
 * and turns every ₹ into three characters of noise.
 */
final class Csv {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final StringBuilder out = new StringBuilder();

    Csv row(List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
        return this;
    }

    byte[] bytes() {
        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, all, 0, BOM.length);
        System.arraycopy(body, 0, all, BOM.length, body.length);
        return all;
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 && !(value instanceof Number)) {
            text = "'" + text;
        }
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                || (!text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(text.length() - 1) == ' '));
        return quote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }
}
