package com.globalfutservice.domain.payments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deciding what an uploaded file is by looking at it.
 *
 * <p>The refusals are the point of this file. Accepting the wrong thing here is not a
 * rendering bug: the type decided here is echoed back as {@code Content-Type} when an
 * operator opens the file, from our own origin, into a session that can release orders
 * and reveal EA sign-ins. An SVG accepted here is stored cross-site scripting against the
 * admin console, uploaded by the customer.
 */
class ImageTypeTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[Math.max(values.length, 16)];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Nested
    @DisplayName("recognises")
    class Recognises {

        @Test
        @DisplayName("a JPEG")
        void jpeg() {
            assertThat(ImageType.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo(ImageType.JPEG);
        }

        @Test
        @DisplayName("a PNG, including the line-ending bytes in its signature")
        void png() {
            assertThat(ImageType.sniff(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)))
                    .isEqualTo(ImageType.PNG);
        }

        @Test
        @DisplayName("a WebP, by its RIFF container and form type")
        void webp() {
            assertThat(ImageType.sniff(bytes(
                    'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P')))
                    .isEqualTo(ImageType.WEBP);
        }
    }

    @Nested
    @DisplayName("refuses")
    class Refuses {

        @Test
        @DisplayName("an SVG, which is a script host wearing an image's file extension")
        void svg() {
            byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\">"
                    + "<script>fetch('/api/v1/admin/orders')</script></svg>")
                    .getBytes(StandardCharsets.UTF_8);

            assertThat(ImageType.sniff(svg)).isNull();
        }

        @Test
        @DisplayName("HTML, whatever it is named")
        void html() {
            assertThat(ImageType.sniff("<!doctype html><script>alert(1)</script>"
                    .getBytes(StandardCharsets.UTF_8))).isNull();
        }

        @Test
        @DisplayName("a PDF receipt, which is the honest mistake this rejects")
        void pdf() {
            assertThat(ImageType.sniff("%PDF-1.7\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1)))
                    .isNull();
        }

        @Test
        @DisplayName("a RIFF container that is not a WebP")
        void riffButNotWebp() {
            // "RIFF" alone is also AVI and WAV. Checking only the first four bytes would
            // store a video as image/webp and serve it back under that type.
            assertThat(ImageType.sniff(bytes(
                    'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'A', 'V', 'I', ' ')))
                    .isNull();
        }

        @Test
        @DisplayName("a file whose extension lies about its contents")
        void renamedFile() {
            // The whole reason the request's own Content-Type is discarded: a caller can
            // name a file screenshot.png and declare it image/png. Only the bytes decide.
            assertThat(ImageType.sniff("GIF89a".getBytes(StandardCharsets.UTF_8))).isNull();
        }

        @Test
        @DisplayName("something too short to have a signature")
        void tooShort() {
            assertThat(ImageType.sniff(new byte[]{(byte) 0xFF, (byte) 0xD8})).isNull();
        }

        @Test
        @DisplayName("nothing at all")
        void empty() {
            assertThat(ImageType.sniff(null)).isNull();
            assertThat(ImageType.sniff(new byte[0])).isNull();
        }
    }

    @Test
    @DisplayName("every accepted type is a raster format that cannot execute")
    void allowListIsRasterOnly() {
        for (ImageType type : ImageType.values()) {
            assertThat(type.mediaType())
                    .as("%s must not be a scriptable type", type)
                    .isIn("image/jpeg", "image/png", "image/webp");
        }
    }
}
