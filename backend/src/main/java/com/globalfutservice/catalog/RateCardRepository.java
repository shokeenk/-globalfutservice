package com.globalfutservice.catalog;

import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RateCardRepository extends JpaRepository<RateCardEntity, Long> {

    /**
     * The live row for one sellable combination.
     *
     * <p>Null-safe on platform and variant: {@code TRADING_SERVICE} rows have a platform
     * and no variant, boost rows have a variant and no platform, and a naive
     * {@code findBy...} would silently miss half of them.
     */
    @Query("""
            select r from RateCardEntity r
            where r.validTo is null
              and r.season = :season
              and r.sku = :sku
              and (:platform is null or r.platform = :platform)
              and (:variant is null or r.variant = :variant)
              and r.currency = :currency
            """)
    Optional<RateCardEntity> findLive(@Param("season") String season,
                                      @Param("sku") Sku sku,
                                      @Param("platform") Platform platform,
                                      @Param("variant") String variant,
                                      @Param("currency") Currency currency);

    @Query("""
            select r from RateCardEntity r
            where r.validTo is null and r.season = :season and r.currency = :currency
            order by r.sku, r.sortOrder
            """)
    List<RateCardEntity> findLiveForSeason(@Param("season") String season,
                                           @Param("currency") Currency currency);

    @Query("""
            select distinct r.currency from RateCardEntity r
            where r.validTo is null and r.season = :season
            """)
    List<Currency> findLiveCurrencies(@Param("season") String season);

    /** Price history for one combination, newest first — the admin audit view. */
    @Query("""
            select r from RateCardEntity r
            where r.season = :season and r.sku = :sku and r.currency = :currency
            order by r.validFrom desc
            """)
    List<RateCardEntity> findHistory(@Param("season") String season,
                                     @Param("sku") Sku sku,
                                     @Param("currency") Currency currency);
}
