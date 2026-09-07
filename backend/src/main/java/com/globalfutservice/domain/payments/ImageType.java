package com.globalfutservice.domain.payments;

/**
 * What an uploaded file actually is, decided by looking at it.
 *
 * <p>The browser sends a {@code Content-Type} with every upload and it is worth nothing:
 * it is chosen by the caller, and a caller who wants to store something other than an
 * image will say whatever gets it accepted. It matters because the stored type is echoed
 * back as the {@code Content-Type} when an operator opens the file -- so trusting the
 * request would let an uploader pick the type their file is later served as, from our own
 * origin, to a signed-in operator.
 *
 * <p>Three raster formats and nothing else. In particular <b>no SVG</b>: an SVG is a
 * document that executes script when a browser renders it, so accepting one would be
 * accepting stored cross-site scripting against the admin console, uploaded by the
 * customer. JPEG, PNG and WebP cannot carry script, which is why this is an allow-list of
 * three signatures rather than a check that the type starts with {@code image/}.
 */
public enum ImageType {

    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private final String mediaType;
    private final String extension;

    ImageType(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }

    /**
     * Identifies the bytes, or returns null when they are not one of the three.
     *
     * <p>Signatures only. This deliberately does not attempt to decode the image: a
     * decoder is a large attack surface to point at hostile input, and the property that
     * matters here is not "this renders" but "this cannot execute".
     */
    public static ImageType sniff(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }

        // FF D8 FF -- SOI followed by the first marker.
        if (u(data[0]) == 0xFF && u(data[1]) == 0xD8 && u(data[2]) == 0xFF) {
            return JPEG;
        }

        // 89 "PNG" CR LF SUB LF. The CR/LF pair is in the signature precisely so that a
        // transfer which mangles line endings corrupts the magic rather than the image.
        if (u(data[0]) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && u(data[4]) == 0x0D && u(data[5]) == 0x0A
                && u(data[6]) == 0x1A && u(data[7]) == 0x0A) {
            return PNG;
        }

        // RIFF....WEBP -- a RIFF container whose form type is WEBP. Both halves are
        // required: "RIFF" alone is also AVI and WAV.
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return WEBP;
        }

        return null;
    }

    private static int u(byte b) {
        return b & 0xFF;
    }
}
