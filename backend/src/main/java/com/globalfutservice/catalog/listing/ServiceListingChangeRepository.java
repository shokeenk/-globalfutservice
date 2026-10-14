package com.globalfutservice.catalog.listing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceListingChangeRepository extends JpaRepository<ServiceListingChangeEntity, Long> {

    List<ServiceListingChangeEntity> findTop10ByListingIdOrderByAtDescIdDesc(Long listingId);
}
