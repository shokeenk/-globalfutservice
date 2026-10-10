package com.globalfutservice.payments.payop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.PaymentAlert;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which Payop methods a customer in a given country is offered.
 *
 * <p>A method is offered only when both sides know it: Payop lists it as available to this
 * project (fetched live, cached for {@code gfs.payop.methods-cache}), and the fee table
 * prices it and has it switched on. Payop's list decides the countries. A method only
 * Payop lists is hidden -- there is no fee to charge for it -- and staff are told once; a
 * method only the fee table lists is hidden quietly, since Payop would refuse it anyway.
 *
 * <p>The currency of the order does not narrow the list: Payop converts an invoice into the
 * method's own processing currency itself.
 */
@Service
public class PayopMethodsService {

    private static final Logger log = LoggerFactory.getLogger(PayopMethodsService.class);

    /** How long a stale list is still used while Payop cannot be reached. */
    static final Duration STALE_LIMIT = Duration.ofHours(6);

    /** One method a customer can pick: its fee row, and Payop's own entry for it. */
    public record Offered(PayopFeeMethodEntity fee, PayopClient.AvailableMethod live) {

        /** Whether it takes cards: Payop's type for it, or the fee table's, names cards. */
        public boolean card() {
            return isCard(fee, live);
        }
    }

    /**
     * For staff: one method Payop lists for a country, with its fee row if the table has one,
     * and whether customers there are offered it -- live, priced and switched on.
     */
    public record Listed(PayopClient.AvailableMethod live, PayopFeeMethodEntity fee, boolean card, boolean offered) {
    }

    /** Whether a method takes cards ("cards_international", "cards_local", "card"...). */
    public static boolean isCard(PayopFeeMethodEntity fee, PayopClient.AvailableMethod live) {
        return mentionsCard(live == null ? null : live.type()) || mentionsCard(fee == null ? null : fee.getMethodType());
    }

    private static boolean mentionsCard(String type) {
        return type != null && type.toLowerCase(java.util.Locale.ROOT).contains("card");
    }

    /** What the customer can pick, or that Payop could not be asked just now. */
    public record Availability(List<Offered> methods, boolean unavailable) {
    }

    private record Cached(List<PayopClient.AvailableMethod> methods, Instant fetchedAt) {
    }

    private final PayopClient client;
    private final PayopFeeMethodRepository fees;
    private final NotificationService notifications;
    private final AppProperties props;
    private final Clock clock;
    private volatile Cached cache;
    /** Unpriced methods staff have already been told about, so each is raised once. */
    private final Set<Long> unpricedAlerted = ConcurrentHashMap.newKeySet();

    public PayopMethodsService(PayopClient client, PayopFeeMethodRepository fees, NotificationService notifications,
                               AppProperties props, Clock clock) {
        this.client = client;
        this.fees = fees;
        this.notifications = notifications;
        this.props = props;
        this.clock = clock;
    }

    /** The methods offered in {@code country} (an ISO code), sorted by name. */
    @Transactional(readOnly = true)
    public Availability forCountry(String country) {
        Optional<List<PayopClient.AvailableMethod>> live = live();
        if (live.isEmpty()) {
            return new Availability(List.of(), true);
        }
        Map<Long, PayopFeeMethodEntity> priced = fees.findByActiveTrue().stream()
                .collect(Collectors.toMap(PayopFeeMethodEntity::getMethodId, Function.identity()));
        List<PayopClient.AvailableMethod> unpriced = new ArrayList<>();
        List<Offered> out = new ArrayList<>();
        for (PayopClient.AvailableMethod m : live.get()) {
            PayopFeeMethodEntity fee = priced.get(m.id());
            if (fee == null) {
                unpriced.add(m);
            } else if (servesCountry(m, fee, country)) {
                out.add(new Offered(fee, m));
            }
        }
        alertUnpriced(unpriced);
        out.sort(Comparator.comparing((Offered o) -> o.fee().getName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(o -> o.fee().getMethodId()));
        return new Availability(out, false);
    }

    /**
     * For staff checking what Payop offers: every method Payop lists for {@code country} --
     * or lists for no country in particular -- fetched fresh, each with its fee row, if any,
     * and whether customers there are offered it.
     */
    @Transactional(readOnly = true)
    public List<Listed> check(String country) {
        refresh();
        List<PayopClient.AvailableMethod> live = live().orElseThrow(() -> new ApiExceptions.ConflictException(
                "payop_unavailable", "Payop's list of methods could not be fetched just now. Try again in a minute."));
        Map<Long, PayopFeeMethodEntity> rows = fees.findAll().stream()
                .collect(Collectors.toMap(PayopFeeMethodEntity::getMethodId, Function.identity()));
        List<Listed> out = new ArrayList<>();
        for (PayopClient.AvailableMethod m : live) {
            if (!m.countries().isEmpty() && !m.countries().contains(country)) {
                continue;
            }
            PayopFeeMethodEntity fee = rows.get(m.id());
            boolean offered = fee != null && fee.isActive() && servesCountry(m, fee, country);
            out.add(new Listed(m, fee, isCard(fee, m), offered));
        }
        out.sort(Comparator.comparing((Listed l) -> l.live().title() == null ? "" : l.live().title(),
                String.CASE_INSENSITIVE_ORDER).thenComparing(l -> l.live().id()));
        return out;
    }

    /** One method, if it is offered in {@code country} right now. */
    @Transactional(readOnly = true)
    public Optional<Offered> find(long methodId, String country) {
        return forCountry(country).methods().stream().filter(o -> o.fee().getMethodId() == methodId).findFirst();
    }

    /** Drops the cached list, so the next request asks Payop again. */
    public void refresh() {
        cache = null;
    }

    /**
     * Payop's list, from the cache while it is fresh. When Payop cannot be reached a list up
     * to {@link #STALE_LIMIT} old is still used; older than that, nothing is offered.
     */
    private Optional<List<PayopClient.AvailableMethod>> live() {
        Instant now = clock.instant();
        Cached held = cache;
        if (held != null && Duration.between(held.fetchedAt(), now).compareTo(props.payop().methodsCache()) < 0) {
            return Optional.of(held.methods());
        }
        try {
            List<PayopClient.AvailableMethod> fetched = List.copyOf(client.availableMethods());
            cache = new Cached(fetched, now);
            return Optional.of(fetched);
        } catch (PayopClient.PayopException | IllegalStateException e) {
            if (held != null && Duration.between(held.fetchedAt(), now).compareTo(STALE_LIMIT) < 0) {
                log.warn("Payop's method list could not be refreshed; using the one from {}", held.fetchedAt());
                return Optional.of(held.methods());
            }
            log.warn("Payop's method list is unavailable; Payop is not offered until it is");
            return Optional.empty();
        }
    }

    /**
     * Payop's countries decide. A method Payop lists with no countries at all is taken to
     * be unrestricted on its side, and the fee table's countries ("*" for everywhere) apply.
     */
    private static boolean servesCountry(PayopClient.AvailableMethod m, PayopFeeMethodEntity fee, String country) {
        if (!m.countries().isEmpty()) {
            return m.countries().contains(country);
        }
        List<String> priced = fee.countryList();
        return priced.contains(CountryNames.EVERYWHERE) || priced.contains(country);
    }

    private void alertUnpriced(List<PayopClient.AvailableMethod> unpriced) {
        List<PayopClient.AvailableMethod> fresh = unpriced.stream().filter(m -> unpricedAlerted.add(m.id())).toList();
        if (fresh.isEmpty()) {
            return;
        }
        String list = fresh.stream().map(m -> m.id() + " " + (m.title() == null ? "" : m.title()).trim())
                .collect(Collectors.joining(", "));
        log.warn("Payop offers {} method(s) the fee table does not price; hidden: {}", fresh.size(), list);
        notifications.paymentAlert(new PaymentAlert(null,
                fresh.size() == 1 ? "A Payop method has no fee" : fresh.size() + " Payop methods have no fee",
                "Payop lists these as available, but the fee table does not price them, so customers are not "
                        + "offered them: " + list + ". If they should be offered, add each one's fee in "
                        + "Admin -> Payments -> International (Payop) -> Fee table, or add them to the pricing "
                        + "sheet and import it again; otherwise ask Payop to switch them off.",
                "METHOD_UNPRICED", props.publicUrl() + "/admin/payop"));
    }
}
