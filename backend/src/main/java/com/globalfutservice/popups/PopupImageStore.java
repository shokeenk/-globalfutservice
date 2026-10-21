package com.globalfutservice.popups;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
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
 * <p><b>Why large objects, not BYTEA.</b> A BYTEA value comes back from the driver whole:
 * serving a 1.5 MB image would hold 1.5 MB per request on a 512 MB server, and a burst of
 * first visitors would hold it many times over. A large object is read in chunks, so
 * {@link #stream} asks the server for {@link #CHUNK} bytes at a time ({@code lo_get} with an
 * offset) and copies each to the response, so it never has more of the image in memory
 * than that. Reads run in one read-only transaction, so an image cannot change under them.
 *
 * <p>A row is never changed. A new upload is a new row with a new id, and the URL carries
 * the id and the first characters of the SHA-256, so it can be cached for a year.
 */
@Component
public class PopupImageStore {

    static final int CHUNK = 64 * 1024;

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
     * Copies the image to {@code out}, {@link #CHUNK} bytes at a time. Headers are the
     * caller's, set before this is called.
     */
    public void stream(long id, OutputStream out) {
        readOnly.executeWithoutResult(s -> {
            Integer size = jdbc.queryForObject("select size_bytes from popup_image where id = ?", Integer.class, id);
            try {
                for (long offset = 0; offset < size; offset += CHUNK) {
                    byte[] chunk = jdbc.queryForObject(
                            "select lo_get(data_oid, ?, ?) from popup_image where id = ?",
                            byte[].class, offset, CHUNK, id);
                    if (chunk == null || chunk.length == 0) {
                        break;
                    }
                    out.write(chunk);
                }
                out.flush();
            } catch (IOException e) {
                // The browser went away mid-image. Nothing to do but stop.
                throw new IllegalStateException("image stream interrupted", e);
            }
        });
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
