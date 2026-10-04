package com.globalfutservice.payments.payop;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FxRateRepository extends JpaRepository<FxRateEntity, Long> {

    /** The newest rate of one source for one currency. */
    Optional<FxRateEntity> findFirstByQuoteAndSourceOrderByRateDateDescIdDesc(String quote, String source);

    boolean existsByQuoteAndSourceAndRateDate(String quote, String source, LocalDate rateDate);

    List<FxRateEntity> findTop50ByOrderByFetchedAtDescIdDesc();
}
