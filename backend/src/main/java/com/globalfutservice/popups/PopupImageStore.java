package com.globalfutservice.popups;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Popup images, in PostgreSQL. See V59.
 *
 * <p><b>Why the database.</b> Render wipes the disk on every deploy, and nothing else here
 * stores files: listing artwork and payment screenshots are in Postgres too.
 *
 * <p><b>Reading: whole, then let go of the connection.</b> {@link #read} reads the image in
 * {@link #CHUNK}-byte pieces ({@code lo_get} with an offset) inside one short read-only
 * transaction, and returns the bytes; only then does the caller write them to the browser.
 * It once copied each piece straight to the response from inside the transaction, which
 * held a pooled connection for as long as the browser took to take the bytes -- and a phone
 * on a bad network, or a connection left half-open, took forever. A few of those held every
 * connection in the pool and took the site down (2026-10-23). An image is at most 1.5 MB.
 *
 * <p><b>A small cache.</b> An image never changes once stored, so the bytes are kept in
 * memory, newest-used first, up to {@link #CACHE_BYTES} in all: a popup on the home page is
 * read from the database once, not on every first visit.
 *
 * <p>A row is never changed. A new upload is a new row with a new id, and the URL carries
 * the id and the first characters of the SHA-256, so it can be cached for a year.
 */
@Component
public class PopupImageStore {

    static final int CHUNK = 64 * 1024;

    /** All cached images together: a handful of popups' desktop and phone images. */
    static final long CACHE_BYTES = 8L * 1024 * 1024;

    /** Bytes by image id, least recently used first. Guarded by itself. */
    private final LinkedHashMap<Long, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedBytes;

    public record ImageRow(long id, long campaignId, String contentType, int sizeBytes, int width, int height,
                           String sha256) {

        /** The short hash in the URL: the version, so a changed image is a changed URL. */
        public String version() {
            return sha256.substring(0, 12);
        }

        public String extension() {
            return switch (contentType) {
                case "image/png" -> "png";
                case "image/webp" -> "webp";
                default -> "jpg";
            };
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnly;

    public PopupImageStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** Stores checked bytes as a large object and returns its row. */
    public ImageRow store(long campaignId, byte[] bytes, PopupImageCheck.Image image, Long adminId) {
        String sha = sha256(bytes);
        return tx.execute(s -> {
            Long id = jdbc.queryForObject("""
                    insert into popup_image (campaign_id, content_type, size_bytes, width, height, sha256, data_oid,
                                             uploaded_by)
                    values (?, ?, ?, ?, ?, ?, lo_from_bytea(0, ?), ?) returning id
                    """, Long.class, campaignId, image.contentType(), bytes.length, image.width(), image.height(),
                    sha, bytes, adminId);
            return new ImageRow(id, campaignId, image.contentType(), bytes.length, image.width(), image.height(), sha);
        });
    }

    public Optional<ImageRow> find(long id) {
        List<ImageRow> rows = jdbc.query("""
                select id, campaign_id, content_type, size_bytes, width, height, sha256 from popup_image where id = ?
                """, (rs, i) -> new ImageRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                rs.getInt(5), rs.getInt(6), rs.getString(7)), id);
        return rows.stream().findFirst();
    }

    /**
     * The whole image, read in one short read-only transaction that is closed before this
     * returns: the caller writes the bytes to the browser with no connection held.
     */
    public byte[] read(long id) {
        synchronized (cache) {
            byte[] hit = cache.get(id);
            if (hit != null) {
                return hit;
            }
        }
        byte[] bytes = readOnly.execute(s -> {
            Integer size = jdbc.queryForObject("select size_bytes from popup_image where id = ?", Integer.class, id);
            ByteArrayOutputStream out = new ByteArrayOutputStream(size);
            for (long offset = 0; offset < size; offset += CHUNK) {
                byte[] chunk = jdbc.queryForObject("select lo_get(data_oid, ?, ?) from popup_image where id = ?",
                        byte[].class, offset, CHUNK, id);
                if (chunk == null || chunk.length == 0) {
                    break;
                }
                out.writeBytes(chunk);
            }
            return out.toByteArray();
        });
        remember(id, bytes);
        return bytes;
    }

    private void remember(long id, byte[] bytes) {
        if (bytes.length > CACHE_BYTES) {
            return;
        }
        synchronized (cache) {
            if (cache.put(id, bytes) == null) {
                cachedBytes += bytes.length;
            }
            Iterator<Map.Entry<Long, byte[]>> oldest = cache.entrySet().iterator();
            while (cachedBytes > CACHE_BYTES && oldest.hasNext()) {
                cachedBytes -= oldest.next().getValue().length;
                oldest.remove();
            }
        }
    }

    /** Removes images and their bytes. Rows still pointed at must be let go first. */
    public void delete(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(s -> {
            for (Long id : ids) {
                jdbc.query("select lo_unlink(data_oid) from popup_image where id = ?", rs -> { }, id);
                jdbc.update("delete from popup_image where id = ?", id);
            }
        });
        synchronized (cache) {
            for (Long id : ids) {
                byte[] gone = cache.remove(id);
                if (gone != null) {
                    cachedBytes -= gone.length;
                }
            }
        }
    }

    /** Every image of a campaign, newest first. */
    public List<Long> idsFor(long campaignId) {
        return jdbc.queryForList("select id from popup_image where campaign_id = ? order by id desc", Long.class,
                campaignId);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
