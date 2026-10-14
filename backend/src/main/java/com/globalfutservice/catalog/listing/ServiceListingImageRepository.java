package com.globalfutservice.catalog.listing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ServiceListingImageRepository extends JpaRepository<ServiceListingImageEntity, Long> {

    /** Width, height and size, without reading the bytes. */
    interface Info {
        Long getId();

        int getWidth();

        int getHeight();

        int getSizeBytes();
    }

    @Query("select i.id as id, i.width as width, i.height as height, i.sizeBytes as sizeBytes "
            + "from ServiceListingImageEntity i where i.id = :id")
    Optional<Info> findInfo(@Param("id") Long id);
}
