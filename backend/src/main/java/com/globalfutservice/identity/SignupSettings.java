package com.globalfutservice.identity;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Pre-tick marketing consent": whether the sign-up form's marketing box starts ticked.
 *
 * <p>Off unless an admin turns it on. A box that arrives ticked is not valid consent for
 * customers in the EU (GDPR recital 32, and the CJEU's Planet49 ruling); that is the
 * client's call to make, with the default on the safe side. It only decides how the box
 * starts: what is recorded is what the customer submits.
 */
@Service
public class SignupSettings {

    private static final Logger log = LoggerFactory.getLogger(SignupSettings.class);

    private final SignupSettingsRepository repository;
    private final Clock clock;

    public SignupSettings(SignupSettingsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean prefillMarketingConsent() {
        return repository.findById(SignupSettingsEntity.ONLY)
                .map(SignupSettingsEntity::isPrefillMarketingConsent).orElse(false);
    }

    @Transactional
    public boolean setPrefillMarketingConsent(boolean on, Long adminId) {
        SignupSettingsEntity row = repository.findById(SignupSettingsEntity.ONLY)
                .orElseGet(SignupSettingsEntity::new);
        row.update(on, adminId, clock.instant());
        repository.save(row);
        log.info("Pre-tick marketing consent set to {} by account {}", on, adminId);
        return on;
    }
}
