package com.globalfutservice.catalog.listing;

import com.globalfutservice.domain.catalog.Sku;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ServiceListingRepository extends JpaRepository<ServiceListingEntity, Long> {

    /** Every listing not deleted, for the admin: by display order, then oldest first. */
    @Query("""
            select l from ServiceListingEntity l
            where l.sku = :sku and l.deletedAt is null
            order by l.displayOrder, l.id
            """)
    List<ServiceListingEntity> findForAdmin(@Param("sku") Sku sku);

    /** What the storefront sells: active and not deleted, in display order. */
    @Query("""
            select l from ServiceListingEntity l
            where l.sku in :skus and l.active = true and l.deletedAt is null
            order by l.displayOrder, l.id
            """)
    List<ServiceListingEntity> findOnSale(@Param("skus") Collection<Sku> skus);

    Optional<ServiceListingEntity> findBySeedKey(String seedKey);

    /** Whether any listing, deleted or not, already has this title. */
    @Query("select count(l) > 0 from ServiceListingEntity l where lower(trim(l.title)) = lower(trim(:title))")
    boolean existsByTitleIgnoreCase(@Param("title") String title);
}
