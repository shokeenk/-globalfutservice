package com.globalfutservice.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.domain.crypto.EnvelopeCipher;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The vault.
 *
 * <p>Seven rules, each of which exists because of a specific way this goes wrong:
 *
 * <ol>
 *   <li><b>Nothing is stored before payment.</b> An endpoint that accepts EA sign-ins on
 *       an unpaid order is a credential-harvesting funnel with a company's name on it.</li>
 *   <li><b>Sealed before it touches the database</b>, with a per-order data key.</li>
 *   <li><b>Never logged.</b> The request DTO overrides {@code toString}, this class never
 *       logs a payload, and the plaintext exists only as a local variable.</li>
 *   <li><b>A hard time-to-live</b>, enforced by a scheduled job independently of the
 *       order state machine — so a stuck order cannot mean an indefinitely retained
 *       password.</li>
 *   <li><b>Every read is counted and attributed.</b> Staff are in the threat model.</li>
 *   <li><b>Purging is irreversible</b> and leaves no key material behind; a database
 *       CHECK constraint enforces that even against a bad UPDATE.</li>
 *   <li><b>The customer is told to rotate</b> in the delivery email.</li>
 * </ol>
 */
@Service
public class CredentialVaultService {

    private static final Logger log = LoggerFactory.getLogger(CredentialVaultService.class);

    private final CredentialVaultRepository repository;
    private final EnvelopeCipher cipher;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final Clock clock;

    public CredentialVaultService(CredentialVaultRepository repository, EnvelopeCipher cipher,
                                  ObjectMapper mapper, AppProperties props, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Seals and stores a sign-in against an order.
     *
     * <p>Overwrites any previous submission for the same order — customers mistype
     * passwords, and a second attempt must replace the first rather than accumulate.
     */
    @Transactional
    public void store(Long orderId, CredentialDtos.SubmitCredentialsRequest request) {
        String plaintext;
        try {
            // Serialise the minimum: the acknowledgement booleans are audit metadata and
            // belong on the order, not inside an encrypted blob.
            plaintext = mapper.writeValueAsString(new StoredCredentials(
                    request.eaEmail(),
                    request.eaPassword(),
                    request.backupCodes() == null ? List.of() : request.backupCodes(),
                    request.platformHandle(),
                    request.note()));
        } catch (Exception e) {
            // No cause attached: the exception message from a serialisation failure can
            // quote the value that failed.
            throw new ApiExceptions.BadRequestException("Those details could not be saved.");
        }

        EnvelopeCipher.Sealed sealed = cipher.seal(plaintext);
        Instant purgeAfter = clock.instant().plus(props.fulfilment().credentialRetention());

        // Upsert. The id is the order id rather than a generated key, so a second
        // submission replaces the first — customers mistype passwords, and a retry
        // must overwrite rather than accumulate copies of a sign-in.
        repository.save(new CredentialVaultEntity(
                orderId, sealed.ciphertext(), sealed.iv(), sealed.wrappedDek(), purgeAfter));

        log.info("Sealed credentials for order id {} (purge after {})", orderId, purgeAfter);
    }

    /**
     * Opens the vault for an operator who is about to fulfil the order.
     *
     * <p>The access is recorded before the plaintext is returned, so a read that is
     * interrupted still leaves a trace.
     */
    @Transactional
    public CredentialDtos.RevealedCredentials reveal(Long orderId, Long operatorId) {
        CredentialVaultEntity vault = repository.findById(orderId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException(
                        "No sign-in has been submitted for this order."));

        if (vault.isPurged()) {
            throw new ApiExceptions.NotFoundException(
                    "These details have been deleted, as promised to the customer.");
        }

        vault.recordAccess(operatorId, clock.instant());
        repository.save(vault);
        log.info("Operator {} opened the vault for order id {} (access #{})",
                operatorId, orderId, vault.getAccessedCount());

        String json = cipher.open(new EnvelopeCipher.Sealed(
                vault.getCiphertext(), vault.getIv(), vault.getWrappedDek()));
        try {
            StoredCredentials stored = mapper.readValue(json, StoredCredentials.class);
            return new CredentialDtos.RevealedCredentials(
                    stored.eaEmail(), stored.eaPassword(), stored.backupCodes(),
                    stored.platformHandle(), stored.note());
        } catch (Exception e) {
            throw new ApiExceptions.UpstreamException("Stored credentials could not be read.", e);
        }
    }

    /** Called by the state machine on entry to any terminal state. */
    @Transactional
    public void purge(Long orderId, String reason) {
        repository.findById(orderId).ifPresent(vault -> {
            if (!vault.isPurged()) {
                vault.purge(clock.instant());
                repository.save(vault);
                log.info("Purged credentials for order id {} ({})", orderId, reason);
            }
        });
    }

    @Transactional(readOnly = true)
    public CredentialDtos.VaultStatus status(Long orderId) {
        Optional<CredentialVaultEntity> vault = repository.findById(orderId);
        return vault
                .map(v -> new CredentialDtos.VaultStatus(
                        !v.isPurged(), v.isPurged(),
                        v.getPurgeAfter() == null ? null : v.getPurgeAfter().toString(),
                        v.getAccessedCount()))
                .orElse(new CredentialDtos.VaultStatus(false, false, null, 0));
    }

    /**
     * Sweeps anything past its retention window.
     *
     * <p>Runs independently of the order state machine on purpose. The state machine
     * purges on the happy path; this catches orders that got stuck, were edited by hand,
     * or hit a code path nobody anticipated. Two mechanisms, one promise.
     */
    @Transactional
    public int purgeExpired() {
        Instant now = clock.instant();
        List<CredentialVaultEntity> due = repository.findDueForPurge(now);
        for (CredentialVaultEntity vault : due) {
            vault.purge(now);
        }
        if (!due.isEmpty()) {
            repository.saveAll(due);
            log.info("Retention sweep purged {} credential record(s)", due.size());
        }
        return due.size();
    }

    @Transactional(readOnly = true)
    public long countHeld() {
        return repository.countHeld();
    }

    /** Internal shape of the sealed blob. Deliberately not a public DTO. */
    record StoredCredentials(
            String eaEmail,
            String eaPassword,
            List<String> backupCodes,
            String platformHandle,
            String note) {

        @Override
        public String toString() {
            return "StoredCredentials[redacted]";
        }
    }

    /** Whether this order already has a sealed, un-purged sign-in. */
    @Transactional(readOnly = true)
    public boolean hasCredentials(Long orderId) {
        return orderId != null && repository.existsByOrderIdAndPurgedAtIsNull(orderId);
    }
}
