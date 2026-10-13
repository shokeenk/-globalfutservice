package com.globalfutservice.catalog.listing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A card image is a real PNG, judged from its bytes, and 2MB at most. */
class PngCheckTest {

    static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFDB1825);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    static byte[] jpeg() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("a real PNG passes, with its own proportions")
    void realPng() throws Exception {
        assertThat(PngCheck.check(png(400, 500))).isEqualTo(new PngCheck.Png(400, 500));
    }

    @Test
    @DisplayName("a JPEG, a text file or nothing at all is not a PNG, whatever it is called")
    void notPng() throws Exception {
        for (byte[] bytes : new byte[][] {jpeg(), "hello.png".getBytes(StandardCharsets.UTF_8), new byte[0]}) {
            assertThatThrownBy(() -> PngCheck.check(bytes)).isInstanceOf(PngCheck.NotPng.class);
        }
        assertThatThrownBy(() -> PngCheck.check(jpeg())).hasMessage("That file is not a PNG image. Upload a .png file.");
    }

    @Test
    @DisplayName("a PNG cut short or with a broken checksum is refused")
    void damaged() throws Exception {
        byte[] good = png(40, 50);
        byte[] cut = Arrays.copyOf(good, good.length - 10);
        assertThatThrownBy(() -> PngCheck.check(cut)).hasMessage("That PNG file is damaged or incomplete.");

        byte[] broken = good.clone();
        broken[20] ^= 0x01; // inside IHDR: its checksum no longer matches
        assertThatThrownBy(() -> PngCheck.check(broken)).hasMessage("That PNG file is damaged or incomplete.");

        byte[] trailing = Arrays.copyOf(good, good.length + 4);
        assertThatThrownBy(() -> PngCheck.check(trailing)).hasMessage("That PNG file is damaged or incomplete.");
    }

    @Test
    @DisplayName("more than 2MB is refused before anything else is read")
    void tooLarge() throws Exception {
        byte[] big = Arrays.copyOf(png(10, 10), PngCheck.MAX_BYTES + 1);
        assertThatThrownBy(() -> PngCheck.check(big))
                .hasMessage("The image is larger than 2MB. Save it smaller and upload it again.");
    }
}
