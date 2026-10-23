package com.globalfutservice.popups;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.globalfutservice.popups.web.PopupController;
import com.globalfutservice.testsupport.SmallPool;
import com.zaxxer.hikari.HikariDataSource;
import com.globalfutservice.popups.PopupPublicService.Event;
import com.globalfutservice.popups.PopupService.PopupInput;
import com.globalfutservice.popups.PopupTypes.Page;
import com.globalfutservice.web.ApiExceptions;
import jakarta.persistence.EntityManager;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Popups against the real schema (V59): images as large objects streamed back in chunks,
 * nothing live until published and switched on, the schedule, versions, duplicate, delete
 * and the counters. Runs when GFS_TEST_PG_URL is set, in a throwaway schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PopupPostgresTest {

    private String schema;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate tx;
    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-03T12:00:00Z"));
    private PopupService popups;
    private PopupPublicService live;
    private PopupImageStore images;
    private HikariDataSource pool;
    private JpaTransactionManager transactions;
    private long adminId;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "popups_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // Three connections: one not given back is noticed at once.
        HikariDataSource ds = SmallPool.of(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"), 3);
        pool = ds;
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .initSql("SET search_path TO " + schema + ", public").load().migrate();
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setPackagesToScan("com.globalfutservice");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "none",
                "hibernate.default_schema", schema,
                "hibernate.jdbc.time_zone", "UTC",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
        factory.afterPropertiesSet();
        transactions = new JpaTransactionManager(factory.getObject());
        transactions.setDataSource(ds);
        tx = new TransactionTemplate(transactions);
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        PopupCampaignRepository campaigns = new JpaRepositoryFactory(shared).getRepository(PopupCampaignRepository.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        images = new PopupImageStore(jdbc, transactions);
        popups = new PopupService(campaigns, images, jdbc, mapper, clock);
        live = new PopupPublicService(campaigns, popups, jdbc, clock);
        adminId = jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, password_hash, role)
                values ('acc_admin', 'owner@example.test', 'owner@example.test', 'x', 'ADMIN') returning id
                """, Long.class);
    }

    @AfterAll
    void drop() {
        if (factory != null) {
            factory.destroy();
        }
        if (jdbc != null) {
            jdbc.execute("select lo_unlink(data_oid) from " + schema + ".popup_image");
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
        if (pool != null) {
            pool.close();
        }
    }

    @AfterEach
    void everyConnectionBack() {
        SmallPool.assertAllReturned(pool);
    }

    // ------------------------------------------------------------------ helpers

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return tx.execute(s -> work.get());
    }

    private PopupService.PopupView create(PopupInput input) {
        return inTx(() -> popups.create(input, adminId));
    }

    private PopupInput schedule(Instant starts, Instant ends) {
        PopupInput v = PopupValidationTest.valid();
        return new PopupInput(v.name(), v.type(), v.copy(), v.buttons(), v.targetPages(), v.targetPaths(),
                v.triggerType(), v.triggerValue(), v.triggerEvent(), starts, ends, v.frequency(), v.frequencyDays(),
                v.priority(), null, false);
    }

    private PopupService.PopupView withImage(String id, byte[] bytes, boolean mobile) {
        return inTx(() -> popups.putImage(id, mobile, new MockMultipartFile("file", "any-name.gif", "image/gif", bytes),
                adminId));
    }

    private PopupService.PopupView live(PopupService.PopupView p) throws IOException {
        withImage(p.id(), PopupImageCheckTest.encode("png", 400, 300), false);
        inTx(() -> popups.publish(p.id(), adminId));
        PopupService.PopupView on = inTx(() -> popups.setEnabled(p.id(), true, adminId));
        live.invalidate();
        return on;
    }

    private List<String> servedFor(Page page, String path) {
        live.invalidate();
        return live.forPage(page, path).stream().map(PopupPublicService.PublicPopup::id).toList();
    }

    private long largeObjects() {
        return jdbc.queryForObject("select count(*) from pg_largeobject_metadata m join " + schema
                + ".popup_image i on i.data_oid = m.oid", Long.class);
    }

    // -------------------------------------------------------------------- tests

    @Test
    @DisplayName("a new popup is a draft, switched off, and nothing is served until it is published AND switched on")
    void nothingLiveByDefault() throws IOException {
        PopupService.PopupView p = create(schedule(null, null));
        assertThat(p.status()).isEqualTo(PopupTypes.Status.DRAFT);
        assertThat(p.enabled()).isFalse();
        assertThat(servedFor(Page.HOME, "/")).doesNotContain(p.id());

        withImage(p.id(), PopupImageCheckTest.encode("png", 400, 300), false);
        inTx(() -> popups.publish(p.id(), adminId));
        assertThat(servedFor(Page.HOME, "/")).doesNotContain(p.id());
        inTx(() -> popups.setEnabled(p.id(), true, adminId));
        assertThat(servedFor(Page.HOME, "/")).contains(p.id());
        assertThat(servedFor(Page.COINS, null)).contains(p.id());
        assertThat(servedFor(null, "/help/")).contains(p.id());
        assertThat(servedFor(Page.REWARDS, "/rewards")).doesNotContain(p.id());

        inTx(() -> popups.setEnabled(p.id(), false, adminId));
        assertThat(servedFor(Page.HOME, "/")).doesNotContain(p.id());
    }

    @Test
    @DisplayName("the public list returns nothing before the schedule starts or after it ends")
    void schedule() throws IOException {
        Instant now = clock.instant();
        PopupService.PopupView future = live(create(schedule(now.plus(Duration.ofHours(1)), null)));
        PopupService.PopupView past = live(create(schedule(null, now.minus(Duration.ofMinutes(1)))));
        PopupService.PopupView inside = live(create(schedule(now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)))));

        assertThat(servedFor(Page.HOME, "/")).contains(inside.id()).doesNotContain(future.id(), past.id());

        // Even from the cache: a campaign that ends is not served a moment later.
        clock.advance(Duration.ofMinutes(61));
        assertThat(live.forPage(Page.HOME, "/")).extracting(PopupPublicService.PublicPopup::id)
                .contains(future.id()).doesNotContain(inside.id(), past.id());
        clock.set(now);
    }

    @Test
    @DisplayName("an image is stored as a large object and read back byte for byte, in 64 KB chunks")
    void imageRoundTrip() throws IOException {
        PopupService.PopupView p = create(schedule(null, null));
        byte[] big = noisyPng(700, 500);
        assertThat(big.length).isGreaterThan(PopupImageStore.CHUNK * 2).isLessThan(PopupImageCheck.MAX_BYTES);
        PopupService.PopupView withIt = withImage(p.id(), big, false);
        PopupService.ImageView image = withIt.desktopImage();
        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.url()).matches("/api/v1/popups/images/\\d+/[0-9a-f]{12}\\.png");

        assertThat(images.read(image.id())).isEqualTo(big);
        // The second read is the cache's: the same bytes.
        assertThat(images.read(image.id())).isEqualTo(big);
    }

    @Test
    @DisplayName("images are checked by their bytes: a renamed GIF is refused; replacing one removes the old bytes")
    void imageRules() throws IOException {
        PopupService.PopupView p = create(schedule(null, null));
        assertThatThrownBy(() -> withImage(p.id(), PopupImageCheckTest.encode("gif", 20, 20), false))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThatThrownBy(() -> withImage(p.id(), new byte[PopupImageCheck.MAX_BYTES + 10], false))
                .isInstanceOf(ApiExceptions.BadRequestException.class);

        long before = largeObjects();
        PopupService.PopupView one = withImage(p.id(), PopupImageCheckTest.encode("jpg", 200, 100), false);
        PopupService.PopupView two = withImage(p.id(), PopupImageCheckTest.encode("png", 300, 100), false);
        assertThat(two.desktopImage().id()).isNotEqualTo(one.desktopImage().id());
        assertThat(images.find(one.desktopImage().id())).isEmpty();
        assertThat(largeObjects()).isEqualTo(before + 1);

        PopupService.PopupView mobile = withImage(p.id(), PopupImageCheckTest.webpVp8x(390, 600), true);
        assertThat(mobile.mobileImage().contentType()).isEqualTo("image/webp");
        assertThat(inTx(() -> popups.removeMobileImage(p.id(), adminId)).mobileImage()).isNull();
        assertThat(largeObjects()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("every edit is a new version and a line in the change log")
    void versionsAndLog() {
        PopupService.PopupView p = create(schedule(null, null));
        PopupService.PopupView edited = inTx(() -> popups.update(p.id(), schedule(null, null), adminId));
        assertThat(edited.version()).isGreaterThan(p.version());
        assertThat(popups.changes(p.id())).extracting(PopupService.Change::action).containsExactly("EDITED", "CREATED");
        assertThat(popups.changes(p.id()).get(0).adminEmail()).isEqualTo("owner@example.test");
    }

    @Test
    @DisplayName("duplicate is a draft, switched off, with its own copy of the images; delete removes it and them")
    void duplicateAndDelete() throws IOException {
        PopupService.PopupView p = live(create(schedule(null, null)));
        long objectsBefore = largeObjects();
        PopupService.PopupView copy = inTx(() -> popups.duplicate(p.id(), adminId));
        assertThat(copy.status()).isEqualTo(PopupTypes.Status.DRAFT);
        assertThat(copy.enabled()).isFalse();
        assertThat(copy.name()).startsWith("Copy of ");
        assertThat(copy.desktopImage().id()).isNotEqualTo(p.desktopImage().id());
        assertThat(largeObjects()).isEqualTo(objectsBefore + 1);

        inTx(() -> { popups.delete(p.id(), adminId); return null; });
        assertThat(servedFor(Page.HOME, "/")).doesNotContain(p.id());
        assertThat(popups.list()).extracting(PopupService.PopupView::id).doesNotContain(p.id()).contains(copy.id());
        assertThatThrownBy(() -> popups.get(p.id())).isInstanceOf(ApiExceptions.NotFoundException.class);
        assertThat(largeObjects()).isEqualTo(objectsBefore);
        // The copy's image is its own and still there.
        assertThat(images.find(copy.desktopImage().id())).isPresent();
    }

    @Test
    @DisplayName("publishing needs a page and the desktop image")
    void publishNeedsImage() {
        PopupService.PopupView p = create(schedule(null, null));
        assertThatThrownBy(() -> inTx(() -> popups.publish(p.id(), adminId)))
                .isInstanceOf(PopupService.PopupInvalid.class);
    }

    @Test
    @DisplayName("counters: daily views, closes and clicks, only for a live popup, and nothing personal stored")
    void counters() throws IOException {
        PopupService.PopupView p = live(create(schedule(null, null)));
        PopupService.PopupView draft = create(schedule(null, null));
        inTx(() -> { live.record(p.id(), Event.VIEW, "198.51.100.1"); live.record(p.id(), Event.VIEW, "198.51.100.2");
            live.record(p.id(), Event.CLOSE, "198.51.100.1"); live.record(p.id(), Event.CLICK, "198.51.100.2");
            live.record(draft.id(), Event.VIEW, "198.51.100.1"); live.record("pop_nonexistent", Event.VIEW, "198.51.100.1");
            return null; });
        PopupService.Stats s = popups.get(p.id()).stats();
        assertThat(List.of(s.views(), s.closes(), s.clicks(), s.views30d())).containsExactly(2L, 1L, 1L, 2L);
        assertThat(popups.get(draft.id()).stats().views()).isZero();
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema = ? "
                + "and table_name = 'popup_stat_daily'", String.class, schema))
                .containsExactlyInAnyOrder("campaign_id", "day", "views", "closes", "clicks");
    }

    @Test
    @DisplayName("counters are limited per IP")
    void countersLimited() throws IOException {
        PopupService.PopupView p = live(create(schedule(null, null)));
        inTx(() -> { for (int i = 0; i < 100; i++) live.record(p.id(), Event.VIEW, "203.0.113.99"); return null; });
        assertThat(popups.get(p.id()).stats().views()).isEqualTo(60);
    }

    /** A PNG too random to compress, so it is several chunks long. */
    private static byte[] noisyPng(int w, int h) throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.util.Random r = new java.util.Random(7);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, r.nextInt());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        void set(Instant at) { now = at; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    // ------------------------------------------------------------------ connections (2026-10-23)

    /** A browser on the other end of the image download: how it takes the bytes. */
    private interface Browser {
        void write(byte[] b, int off, int len) throws IOException;
    }

    /** The image endpoint as the browser calls it, with a fresh store (an empty cache) unless given one. */
    private byte[] download(PopupController controller, PopupService.ImageView image, Browser browser)
            throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        MockHttpServletResponse mock = new MockHttpServletResponse();
        ServletOutputStream out = new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                browser.write(b, off, len);
                received.write(b, off, len);
            }
        };
        String file = image.url().substring(image.url().lastIndexOf('/') + 1);
        controller.image(image.id(), file, new HttpServletResponseWrapper(mock) {
            @Override public ServletOutputStream getOutputStream() { return out; }
        });
        return received.toByteArray();
    }

    private PopupController freshController() {
        return new PopupController(live, new PopupImageStore(jdbc, transactions));
    }

    private PopupService.ImageView bigImage() throws IOException {
        PopupService.PopupView p = create(schedule(null, null));
        return withImage(p.id(), noisyPng(700, 500), false).desktopImage();
    }

    @Test
    @DisplayName("200 image downloads on a pool of 3, from the database every time: every connection comes back")
    void manyDownloads() throws IOException {
        PopupService.ImageView image = bigImage();
        byte[] expected = images.read(image.id());
        for (int i = 0; i < 200; i++) {
            assertThat(download(freshController(), image, (b, off, len) -> { })).isEqualTo(expected);
        }
        SmallPool.assertAllReturned(pool);
    }

    @Test
    @DisplayName("aborted downloads (the browser goes away mid-image) give every connection back")
    void abortedDownloads() throws IOException {
        PopupService.ImageView image = bigImage();
        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(() -> download(freshController(), image, (b, off, len) -> {
                throw new IOException("Broken pipe");
            })).isInstanceOf(IOException.class);
        }
        SmallPool.assertAllReturned(pool);
    }

    @Test
    @DisplayName("ten stalled browsers holding image downloads open hold no connection: the site still answers")
    void stalledBrowsersHoldNoConnection() throws Exception {
        PopupService.ImageView image = bigImage();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(10);
        ExecutorService browsers = Executors.newFixedThreadPool(10);
        try {
            List<Future<byte[]>> downloads = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                downloads.add(browsers.submit(() -> download(freshController(), image, (b, off, len) -> {
                    writing.countDown();
                    try {
                        release.await();   // a phone on a bad network: the bytes go nowhere
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })));
            }
            assertThat(writing.await(10, TimeUnit.SECONDS)).as("all ten are writing").isTrue();
            // Ten downloads stuck mid-write, a pool of three: none of them is holding one.
            assertThat(SmallPool.active(pool)).isZero();
            assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
            release.countDown();
            for (Future<byte[]> d : downloads) {
                assertThat(d.get(10, TimeUnit.SECONDS)).hasSize(images.read(image.id()).length);
            }
        } finally {
            release.countDown();
            browsers.shutdownNow();
        }
        SmallPool.assertAllReturned(pool);
    }
}
