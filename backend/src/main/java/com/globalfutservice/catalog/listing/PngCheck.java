package com.globalfutservice.catalog.listing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Whether some bytes are a real PNG, judged from the bytes themselves -- never from a file
 * name or the type a browser claims.
 *
 * <p>Checked as far as the format allows without decoding the pixels: the signature, an
 * IHDR chunk first with sensible dimensions, every chunk's length and checksum, at least
 * one IDAT, and IEND last with nothing after it. Decoding is deliberately not done: a 2MB
 * PNG can declare an image that takes gigabytes to decompress, and nothing here needs the
 * pixels -- only the card's proportions, which IHDR states.
 */
public final class PngCheck {

    /** The most bytes a card image may have: 2MB. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    /** Generous for a card (400 × 500 is recommended); stops a nonsense header. */
    static final int MAX_SIDE = 4096;

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngCheck() {
    }

    public record Png(int width, int height) {
    }

    /** Refused, with a sentence an admin can act on. */
    public static final class NotPng extends Exception {
        public NotPng(String message) {
            super(message);
        }
    }

    public static Png check(byte[] bytes) throws NotPng {
        if (bytes == null || bytes.length == 0) {
            throw new NotPng("Choose a PNG file.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new NotPng("The image is larger than 2MB. Save it smaller and upload it again.");
        }
        if (bytes.length < SIGNATURE.length || !Arrays.equals(Arrays.copyOf(bytes, SIGNATURE.length), SIGNATURE)) {
            throw new NotPng("That file is not a PNG image. Upload a .png file.");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes);
        in.position(SIGNATURE.length);
        int width = 0;
        int height = 0;
        boolean first = true;
        boolean sawData = false;
        while (true) {
            if (in.remaining() < 12) {
                throw new NotPng("That PNG file is damaged or incomplete.");
            }
            long length = Integer.toUnsignedLong(in.getInt());
            if (length > in.remaining() - 8) {
                throw new NotPng("That PNG file is damaged or incomplete.");
            }
            int start = in.position();
            byte[] type = new byte[4];
            in.get(type);
            String name = new String(type, StandardCharsets.US_ASCII);
            if (first) {
                if (!"IHDR".equals(name) || length != 13) {
                    throw new NotPng("That file is not a PNG image. Upload a .png file.");
                }
                width = in.getInt(in.position());
                height = in.getInt(in.position() + 4);
                first = false;
            }
            in.position(in.position() + (int) length);
            CRC32 crc = new CRC32();
            crc.update(bytes, start, 4 + (int) length);
            if ((int) crc.getValue() != in.getInt()) {
                throw new NotPng("That PNG file is damaged or incomplete.");
            }
            if ("IDAT".equals(name)) {
                sawData = true;
            }
            if ("IEND".equals(name)) {
                if (in.hasRemaining() || !sawData) {
                    throw new NotPng("That PNG file is damaged or incomplete.");
                }
                break;
            }
        }
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
            throw new NotPng("The image must be between 1 and " + MAX_SIDE + " pixels on each side.");
        }
        return new Png(width, height);
    }
}
