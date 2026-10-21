package com.globalfutservice.popups;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JPG, PNG or WebP, at most 1.5 MB, read from the bytes themselves. */
class PopupImageCheckTest {

    static byte[] encode(String format, int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    /** A WebP header: RIFF, WEBP, then the chunk that carries the size. Only the header is read. */
    static byte[] webpVp8x(int w, int h) {
        byte[] b = new byte[40];
        put(b, 0, "RIFF");
        put(b, 8, "WEBP");
        put(b, 12, "VP8X");
        int ww = w - 1;
        int hh = h - 1;
        b[24] = (byte) ww; b[25] = (byte) (ww >> 8); b[26] = (byte) (ww >> 16);
        b[27] = (byte) hh; b[28] = (byte) (hh >> 8); b[29] = (byte) (hh >> 16);
        return b;
    }

    static byte[] webpLossless(int w, int h) {
        byte[] b = new byte[40];
        put(b, 0, "RIFF");
        put(b, 8, "WEBP");
        put(b, 12, "VP8L");
        b[20] = 0x2F;
        int bits = (w - 1) | (h - 1) << 14;
        b[21] = (byte) bits; b[22] = (byte) (bits >> 8); b[23] = (byte) (bits >> 16); b[24] = (byte) (bits >> 24);
        return b;
    }

    private static void put(byte[] b, int at, String s) {
        byte[] x = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(x, 0, b, at, x.length);
    }

    @Test
    @DisplayName("PNG, JPG and WebP are read for their real type and size")
    void accepted() throws Exception {
        assertThat(PopupImageCheck.check(encode("png", 1200, 628)))
                .isEqualTo(new PopupImageCheck.Image("image/png", "png", 1200, 628));
        assertThat(PopupImageCheck.check(encode("jpg", 640, 960)))
                .isEqualTo(new PopupImageCheck.Image("image/jpeg", "jpg", 640, 960));
        assertThat(PopupImageCheck.check(webpVp8x(1080, 1350)))
                .isEqualTo(new PopupImageCheck.Image("image/webp", "webp", 1080, 1350));
        assertThat(PopupImageCheck.check(webpLossless(300, 200)))
                .isEqualTo(new PopupImageCheck.Image("image/webp", "webp", 300, 200));
    }

    @Test
    @DisplayName("anything else is refused, whatever it is called: GIF, BMP, SVG, a script, an empty file")
    void refused() throws Exception {
        for (byte[] bytes : new byte[][] {
                encode("gif", 10, 10), encode("bmp", 10, 10),
                "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8),
                "<?php echo 1; ?>".getBytes(StandardCharsets.UTF_8), new byte[0],
                Arrays.copyOf(encode("png", 10, 10), 12)}) {
            assertThatThrownBy(() -> PopupImageCheck.check(bytes)).isInstanceOf(PopupImageCheck.Refused.class);
        }
    }

    @Test
    @DisplayName("1.5 MB is the limit: one byte over is refused")
    void sizeLimit() throws Exception {
        byte[] png = encode("png", 64, 64);
        byte[] atLimit = Arrays.copyOf(png, PopupImageCheck.MAX_BYTES);
        assertThat(PopupImageCheck.check(atLimit).contentType()).isEqualTo("image/png");
        byte[] over = Arrays.copyOf(png, PopupImageCheck.MAX_BYTES + 1);
        assertThatThrownBy(() -> PopupImageCheck.check(over)).isInstanceOf(PopupImageCheck.Refused.class)
                .hasMessageContaining("1.5 MB");
        assertThat(PopupImageCheck.MAX_BYTES).isEqualTo(1_572_864);
    }

    @Test
    @DisplayName("absurd dimensions are refused")
    void dimensions() {
        assertThatThrownBy(() -> PopupImageCheck.check(webpVp8x(9000, 100))).isInstanceOf(PopupImageCheck.Refused.class);
    }
}
