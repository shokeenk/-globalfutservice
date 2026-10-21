package com.globalfutservice.popups;

/**
 * What an uploaded image really is, read from its bytes -- never from its file name or the
 * Content-Type the browser sent. JPEG, PNG or WebP, at most 1.5 MB, with sane dimensions;
 * anything else (an SVG, a GIF, a renamed file) is refused.
 */
public final class PopupImageCheck {

    public static final int MAX_BYTES = 1_572_864;
    static final int MAX_SIDE = 8000;

    public record Image(String contentType, String extension, int width, int height) {
    }

    public static final class Refused extends Exception {
        public Refused(String message) {
            super(message);
        }
    }

    private PopupImageCheck() {
    }

    public static Image check(byte[] b) throws Refused {
        if (b.length == 0) {
            throw new Refused("That file is empty.");
        }
        if (b.length > MAX_BYTES) {
            throw new Refused("Images can be at most 1.5 MB.");
        }
        Image image;
        if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            image = png(b);
        } else if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
            image = jpeg(b);
        } else if (b.length >= 30 && ascii(b, 0, "RIFF") && ascii(b, 8, "WEBP")) {
            image = webp(b);
        } else {
            throw new Refused("Only JPG, PNG or WebP images can be used.");
        }
        if (image.width() < 1 || image.height() < 1 || image.width() > MAX_SIDE || image.height() > MAX_SIDE) {
            throw new Refused("That image's size could not be read, or it is larger than "
                    + MAX_SIDE + " px on a side.");
        }
        return image;
    }

    private static Image png(byte[] b) throws Refused {
        if (b.length < 24 || !ascii(b, 12, "IHDR")) {
            throw new Refused("That PNG could not be read.");
        }
        return new Image("image/png", "png", u32(b, 16), u32(b, 20));
    }

    /** Walks the segments to the first frame header (SOF), which carries the size. */
    private static Image jpeg(byte[] b) throws Refused {
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                throw new Refused("That JPG could not be read.");
            }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) {
                i++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            int length = u16(b, i + 2);
            boolean frame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (frame) {
                return new Image("image/jpeg", "jpg", u16(b, i + 7), u16(b, i + 5));
            }
            if (length < 2) {
                break;
            }
            i += 2 + length;
        }
        throw new Refused("That JPG could not be read.");
    }

    private static Image webp(byte[] b) throws Refused {
        if (ascii(b, 12, "VP8 ") && (b[23] & 0xFF) == 0x9D && (b[24] & 0xFF) == 0x01 && (b[25] & 0xFF) == 0x2A) {
            return new Image("image/webp", "webp", u16le(b, 26) & 0x3FFF, u16le(b, 28) & 0x3FFF);
        }
        if (ascii(b, 12, "VP8L") && (b[20] & 0xFF) == 0x2F) {
            int bits = (b[21] & 0xFF) | (b[22] & 0xFF) << 8 | (b[23] & 0xFF) << 16 | (b[24] & 0xFF) << 24;
            return new Image("image/webp", "webp", (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1);
        }
        if (ascii(b, 12, "VP8X")) {
            return new Image("image/webp", "webp", u24le(b, 24) + 1, u24le(b, 27) + 1);
        }
        throw new Refused("That WebP could not be read.");
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    private static boolean ascii(byte[] b, int at, String s) {
        if (b.length < at + s.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (b[at + i] != (byte) s.charAt(i)) return false;
        }
        return true;
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
    }

    private static int u32(byte[] b, int at) {
        long v = (long) (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
        return v > Integer.MAX_VALUE ? -1 : (int) v;
    }

    private static int u16le(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    private static int u24le(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16;
    }
}
