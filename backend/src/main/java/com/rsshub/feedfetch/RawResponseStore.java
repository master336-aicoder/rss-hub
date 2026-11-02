package com.rsshub.feedfetch;

import com.rsshub.common.JdbcTimes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 02 §4 step 4: keep the exact bytes the publisher sent, so a later parser fix can re-read history instead of
 * waiting for the feed to republish. Files are content-addressed, which makes re-fetching unchanged bytes free.
 * <p>
 * Known leak: if the surrounding transaction rolls back after the file is written, the file survives with no row.
 * At self-hosted volume that is kilobytes, and the alternative (write files after commit) risks rows pointing at
 * bytes that were never written — the row is the part that cannot be reconstructed.
 */
@Service
public class RawResponseStore {

    private static final Logger log = LoggerFactory.getLogger(RawResponseStore.class);

    private final JdbcClient db;
    private final StorageProperties storage;

    public RawResponseStore(JdbcClient db, StorageProperties storage) {
        this.db = db;
        this.storage = storage;
    }

    /** @param storageKey path relative to {@code rsshub.storage.raw-dir}, never absolute, so the tree can move */
    public record Saved(long id, String storageKey, String sha256, int byteSize) {
    }

    public Saved save(long feedId, int httpStatus, String contentType, byte[] body, Instant fetchedAt) {
        String sha = sha256(body);
        LocalDate day = LocalDate.ofInstant(fetchedAt, ZoneOffset.UTC);
        String key = "%04d/%02d/feed-%d-%s.bin".formatted(day.getYear(), day.getMonthValue(), feedId,
            sha.substring(0, 20));
        write(key, body);

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        db.sql("insert into raw_response (feed_id, fetched_at, http_status, content_type, byte_size, sha256, "
                + "storage_key) values (?, ?, ?, ?, ?, ?, ?)")
            .params(feedId, JdbcTimes.toSql(fetchedAt), httpStatus, contentType, body.length, sha, key)
            // The identity column has to be named: H2 answers getGeneratedKeys() with every column that has a
            // default (id, public_id, created_at, updated_at), and GeneratedKeyHolder.getKey() then refuses.
            .update(keyHolder, "id");
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("raw_response insert returned no identity for feed " + feedId);
        }
        return new Saved(id.longValue(), key, sha, body.length);
    }

    /** 03 §4.3 step 4 keeps {@code entry_count} on this row, but only the parser knows it, so it arrives later. */
    public void attachEntryCount(long rawId, int entryCount) {
        db.sql("update raw_response set entry_count = ? where id = ?").params(entryCount, rawId).update();
    }

    /**
     * Deletes rows (and their files) older than the retention window.
     *
     * @return rows removed
     */
    public int purge(Instant olderThan) {
        List<Map<String, Object>> expired = db.sql("select id, storage_key from raw_response where fetched_at < ?")
            .param(JdbcTimes.toSql(olderThan))
            .query()
            .listOfRows();
        for (Map<String, Object> row : expired) {
            long id = ((Number) row.get("id")).longValue();
            int deleted = db.sql("delete from raw_response where id = ?").param(id).update();
            if (deleted == 1) {
                deleteQuietly((String) row.get("storage_key"));
            }
        }
        if (!expired.isEmpty()) {
            log.info("raw response sweep removed {} fetches older than {}", expired.size(), olderThan);
        }
        return expired.size();
    }

    /**
     * 02 §4 step 4: raw bytes are a 30-day cache, not an archive. They are the only part of the schema that grows
     * without a bound, so this is what keeps a decade of subscriptions on one small disk.
     */
    @Scheduled(cron = "0 25 3 * * *")
    public void sweep() {
        purge(Instant.now().minus(storage.rawRetention()));
    }

    private void write(String key, byte[] body) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            log.atDebug().addKeyValue("key", key).log("raw bytes already on disk, reusing");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store raw response under " + storage.rawDir(), e);
        }
    }

    /** The key comes back out of the database, so it is treated as untrusted and re-checked against the root. */
    private Path resolve(String key) {
        Path root = storage.rawDir().toAbsolutePath().normalize();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("raw response key escapes the storage root: " + key);
        }
        return target;
    }

    private void deleteQuietly(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | IllegalArgumentException e) {
            log.atWarn().setCause(e).addKeyValue("key", key).log("expired raw response file could not be deleted");
        }
    }

    private static String sha256(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest(body)) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }
}
