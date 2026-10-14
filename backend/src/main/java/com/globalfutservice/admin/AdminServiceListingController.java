package com.globalfutservice.admin;

import com.globalfutservice.catalog.listing.ServiceListingService;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Objectives and Tournaments listings: added, edited, duplicated, switched on and off,
 * deleted, and given their card artwork, from Service Listings.
 *
 * <p>ADMIN only, like the rest of Service Listings: this sets what customers see and what
 * they are charged. Every change is recorded with who made it and when.
 */
@RestController
@RequestMapping("/api/v1/admin/service-listings")
@Tag(name = "Admin — Objectives and Tournaments", description = "Listings the GFS team writes")
@PreAuthorize("hasRole('ADMIN')")
public class AdminServiceListingController {

    private final ServiceListingService listings;

    public AdminServiceListingController(ServiceListingService listings) {
        this.listings = listings;
    }

    @GetMapping("/{id}")
    @Operation(summary = "One listing, with who changed it last")
    public ResponseEntity<ServiceListingService.AdminView> one(@PathVariable long id) {
        return ok(listings.one(id));
    }

    @PostMapping
    @Operation(summary = "Add a listing")
    public ResponseEntity<ServiceListingService.AdminView> create(@RequestBody ServiceListingService.Form form,
                                                                  @CurrentAccount AccountPrincipal admin) {
        long id = listings.create(form, adminId(admin));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(listings.one(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Save a listing")
    public ResponseEntity<ServiceListingService.AdminView> update(@PathVariable long id,
                                                                  @RequestBody ServiceListingService.Form form,
                                                                  @CurrentAccount AccountPrincipal admin) {
        listings.update(id, form, adminId(admin));
        return ok(listings.one(id));
    }

    @PostMapping("/{id}/duplicate")
    @Operation(summary = "Copy a listing as an inactive draft")
    public ResponseEntity<ServiceListingService.AdminView> duplicate(@PathVariable long id,
                                                                     @CurrentAccount AccountPrincipal admin) {
        long copy = listings.duplicate(id, adminId(admin));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(listings.one(copy));
    }

    public record References(long orders) {
    }

    @GetMapping("/{id}/references")
    @Operation(summary = "How many orders name this listing, before deleting it")
    public ResponseEntity<References> references(@PathVariable long id) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new References(listings.ordersReferencing(id)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a listing: hidden everywhere, kept for the orders that name it")
    public ResponseEntity<Void> delete(@PathVariable long id, @CurrentAccount AccountPrincipal admin) {
        listings.delete(id, adminId(admin));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload or replace the card artwork: a PNG, 2 MB at most")
    public ResponseEntity<ServiceListingService.AdminView> image(@PathVariable long id,
                                                                 @RequestPart("file") MultipartFile file,
                                                                 @CurrentAccount AccountPrincipal admin) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiExceptions.BadRequestException("That upload could not be read.");
        }
        listings.setImage(id, bytes, adminId(admin));
        return ok(listings.one(id));
    }

    @DeleteMapping("/{id}/image")
    @Operation(summary = "Remove the card artwork")
    public ResponseEntity<ServiceListingService.AdminView> removeImage(@PathVariable long id,
                                                                       @CurrentAccount AccountPrincipal admin) {
        listings.removeImage(id, adminId(admin));
        return ok(listings.one(id));
    }

    private static Long adminId(AccountPrincipal admin) {
        if (admin == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        return admin.id();
    }

    private static ResponseEntity<ServiceListingService.AdminView> ok(ServiceListingService.AdminView view) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(view);
    }
}
