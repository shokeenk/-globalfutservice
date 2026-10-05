package com.globalfutservice.payments.payop;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * EUR exchange rates, for one purpose: turning the fixed part of a Payop fee, which Payop
 * quotes in EUR, into the order's currency.
 *
 * <p>The European Central Bank's daily reference rates first. They are published on working
 * days only, so a rate up to {@code gfs.fx.max-age} old is still used; past that, the newest
 * rate an admin entered. With neither, there is no rate, and Payop is not offered in that
 * currency -- a fee is never worked out at a rate nobody can name.
 *
 * <p>Whatever is used is recorded with the invoice ({@link RateUsed}), so the fee on an old
 * order can always be explained.
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);

    /** The rate a fee used: units of the order's currency per EUR, where it came from, and its date. */
    public record RateUsed(BigDecimal rate, String source, LocalDate date) {
    }

    /** EUR needs no conversion; recorded as such. */
    static final String NONE = "NONE";

    private final FxRateRepository rates;
    private final AppProperties props;
    private final Clock clock;
    private final HttpClient http;

    public FxRateService(FxRateRepository rates, AppProperties props, Clock clock) {
        this.rates = rates;
        this.props = props;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** How many units of {@code currency} one EUR buys, or empty when no usable rate exists. */
    @Transactional(readOnly = true)
    public Optional<RateUsed> eurTo(Currency currency) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (currency == Currency.EUR) {
            return Optional.of(new RateUsed(BigDecimal.ONE, NONE, today));
        }
        LocalDate oldest = today.minusDays(props.fx().maxAge().toDays());
        Optional<FxRateEntity> ecb = rates.findFirstByQuoteAndSourceOrderByRateDateDescIdDesc(
                currency.name(), FxRateEntity.ECB);
        if (ecb.isPresent() && !ecb.get().getRateDate().isBefore(oldest)) {
            return ecb.map(r -> new RateUsed(r.getRate(), r.getSource(), r.getRateDate()));
        }
        return rates.findFirstByQuoteAndSourceOrderByRateDateDescIdDesc(currency.name(), FxRateEntity.ADMIN)
                .map(r -> new RateUsed(r.getRate(), r.getSource(), r.getRateDate()));
    }

    /** Fetches the ECB's latest rates while Payop is on. A failed fetch keeps the rates already held. */
    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT1M")
    public void refreshOnSchedule() {
        if (props.payop().enabled()) {
            refreshFromEcb();
        }
    }

    /** @return how many new rates were stored; 0 when the ECB had nothing new or could not be read */
    @Transactional
    public int refreshFromEcb() {
        EcbRates ecb;
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(props.fx().ecbUrl()))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("ECB rates not fetched: HTTP {}", response.statusCode());
                return 0;
            }
            ecb = parseEcb(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            log.warn("ECB rates not fetched: {}", e.getClass().getSimpleName());
            return 0;
        }
        int stored = 0;
        Instant now = clock.instant();
        for (Currency currency : Currency.values()) {
            BigDecimal rate = ecb.rates().get(currency.name());
            if (currency == Currency.EUR || rate == null
                    || rates.existsByQuoteAndSourceAndRateDate(currency.name(), FxRateEntity.ECB, ecb.date())) {
                continue;
            }
            rates.save(new FxRateEntity(currency.name(), rate, FxRateEntity.ECB, ecb.date(), now, null));
            stored++;
        }
        if (stored > 0) {
            log.info("Stored {} ECB rate(s) for {}", stored, ecb.date());
        }
        return stored;
    }

    /** An admin's rate, used when the ECB's is missing or too old. */
    @Transactional
    public FxRateEntity enterAdminRate(Currency currency, BigDecimal rate, LocalDate date, Long actorId) {
        if (currency == Currency.EUR) {
            throw new IllegalArgumentException("EUR needs no rate");
        }
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("The rate must be more than zero");
        }
        return rates.save(new FxRateEntity(currency.name(), rate, FxRateEntity.ADMIN, date, clock.instant(), actorId));
    }

    @Transactional(readOnly = true)
    public List<FxRateEntity> recent() {
        return rates.findTop50ByOrderByFetchedAtDescIdDesc();
    }

    /** One day of ECB reference rates: the date and each currency's rate against EUR. */
    record EcbRates(LocalDate date, Map<String, BigDecimal> rates) {
    }

    /**
     * Reads the ECB's eurofxref-daily.xml: a {@code Cube time='YYYY-MM-DD'} holding
     * {@code Cube currency='USD' rate='1.1225'} entries. DOCTYPEs and external entities are
     * refused, as for anything fetched from outside.
     */
    static EcbRates parseEcb(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(true);
        NodeList cubes = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getElementsByTagNameNS("*", "Cube");
        LocalDate date = null;
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (int i = 0; i < cubes.getLength(); i++) {
            Element cube = (Element) cubes.item(i);
            if (cube.hasAttribute("time")) {
                date = LocalDate.parse(cube.getAttribute("time"));
            } else if (cube.hasAttribute("currency") && cube.hasAttribute("rate")) {
                BigDecimal rate = new BigDecimal(cube.getAttribute("rate"));
                if (rate.signum() > 0) {
                    out.put(cube.getAttribute("currency"), rate);
                }
            }
        }
        if (date == null || out.isEmpty()) {
            throw new IllegalArgumentException("no rates in the ECB document");
        }
        return new EcbRates(date, out);
    }
}
