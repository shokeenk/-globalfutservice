package com.globalfutservice.config;

import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.payments.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-boot setup and a startup self-check.
 *
 * <p>The admin account is created from environment variables rather than seeded in a
 * migration. A migration containing a password hash is a password hash in version
 * control, on every developer's laptop and in every fork — and the default credentials
 * from a tutorial are among the first things anyone tries against a new deployment.
 *
 * <p>The self-check exists because the most dangerous configuration mistakes are the
 * silent ones. A missing webhook secret does not break anything visible; it just means
 * orders quietly never get marked paid. Saying so loudly at startup is cheap.
 */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final PaymentGateway gateway;
    private final AppProperties props;

    public BootstrapRunner(AccountRepository accounts, PasswordEncoder passwordEncoder,
                           PaymentGateway gateway, AppProperties props) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.gateway = gateway;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createBootstrapAdmin();
        reportConfiguration();
    }

    private void createBootstrapAdmin() {
        String email = props.security().bootstrapAdminEmail();
        String password = props.security().bootstrapAdminPassword();

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            if (accounts.countStaff() == 0) {
                log.warn("""

                        ================================================================
                         No staff account exists and no bootstrap admin is configured.
                         Nobody can sign in to the admin console.

                         Set these and restart:
                           GFS_BOOTSTRAP_ADMIN_EMAIL=you@example.com
                           GFS_BOOTSTRAP_ADMIN_PASSWORD=<at least 12 characters>
                        ================================================================
                        """);
            }
            return;
        }

        String normalised = AccountEntity.normalise(email);
        if (accounts.existsByEmailNormalised(normalised)) {
            log.debug("Bootstrap admin already exists; leaving it alone");
            return;
        }

        if (password.length() < 12) {
            // Refuse rather than create a weak permanent admin. Failing to start is
            // recoverable; a four-character admin password in production is not.
            throw new IllegalStateException(
                    "GFS_BOOTSTRAP_ADMIN_PASSWORD must be at least 12 characters");
        }

        AccountEntity admin = new AccountEntity(
                "acc_" + SecureIds.token(12), email.trim(),
                passwordEncoder.encode(password), AccountRole.ADMIN);
        admin.setDisplayName("Administrator");
        accounts.save(admin);

        log.info("Created bootstrap admin account for {}. "
                + "Change the password and clear the environment variables.", email);
    }

    private void reportConfiguration() {
        StringBuilder warnings = new StringBuilder();

        if (!gateway.isEnabled()) {
            warnings.append("  - Razorpay is disabled: checkout will issue stub orders "
                    + "and no money will move.\n");
        }
        if (props.razorpay().enabled()
                && (props.razorpay().webhookSecret() == null
                || props.razorpay().webhookSecret().isBlank())) {
            warnings.append("  - Razorpay is enabled but no webhook secret is set: paid "
                    + "orders will NEVER be marked paid.\n");
        }
        if (!props.security().requireHttps()) {
            warnings.append("  - requireHttps is false: session cookies are not marked "
                    + "Secure. Correct for local development, wrong anywhere else.\n");
        }
        if (props.security().corsAllowedOrigins().stream().anyMatch(o -> o.contains("*"))) {
            warnings.append("  - A CORS origin contains a wildcard. Use exact origins.\n");
        }
        // A currency in the picker is a promise the gateway has to be able to keep. The
        // rate cards can be authored long before Razorpay international is activated, and
        // the failure mode is invisible until a customer in Madrid reaches checkout and
        // the payment is declined — so it is said out loud at every startup instead.
        if (props.pricing().enabledCurrencies().stream().anyMatch(c -> !"INR".equalsIgnoreCase(c))) {
            warnings.append("  - Currencies other than INR are enabled ")
                    .append(props.pricing().enabledCurrencies())
                    .append(". Razorpay must have international payments activated on the ")
                    .append("merchant account, or those customers cannot pay. The non-INR ")
                    .append("price lists are also unreviewed drafts — see V7.\n");
        }

        log.info("Global FUT Services starting — season {}, currencies {}, delivery default {}",
                props.season(), props.pricing().enabledCurrencies(),
                props.fulfilment().defaultDeliveryMethod());

        if (!warnings.isEmpty()) {
            log.warn("\nConfiguration warnings:\n{}", warnings);
        }
    }
}
