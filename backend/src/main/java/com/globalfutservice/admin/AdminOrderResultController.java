package com.globalfutservice.admin;

import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.result.OrderResultService;
import com.globalfutservice.orders.result.ResultDtos;
import com.globalfutservice.orders.result.SettlementProofEntity;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * The Champions card on the admin order page: Wins Achieved, the settlement and its proof.
 *
 * <p>Operators record the wins and deliver, as they mark any order delivered. The method,
 * Paid, Issued and the proof are an admin's: they say money or credit went to a customer.
 */
@RestController
@RequestMapping("/api/v1/admin/orders/{publicRef}/result")
@Tag(name = "Admin — order results", description = "Champions wins achieved and their settlement")
public class AdminOrderResultController {

    private final OrderService orders;
    private final OrderResultService results;
    private final OrderMapper mapper;
    private final CredentialVaultService vault;

    public AdminOrderResultController(OrderService orders, OrderResultService results, OrderMapper mapper,
                                      CredentialVaultService vault) {
        this.orders = orders;
        this.results = results;
        this.mapper = mapper;
        this.vault = vault;
    }

    public record DeliverRequest(@Valid ResultDtos.ResultInput result,
                                 @Size(max = 500) String reason) {
    }

    @GetMapping
    @Operation(summary = "The order's result: the levels to choose from, each priced, and what is recorded")
    public ResponseEntity<ResultDtos.AdminView> view(@PathVariable String publicRef) {
        OrderEntity order = orders.requireAny(publicRef);
        return ok(results.adminView(order));
    }

    @PostMapping("/deliver")
    @Operation(summary = "Record Wins Achieved and mark the order delivered",
            description = "One transaction. Delivering is final and settles nothing: anything owed is Pending.")
    public ResponseEntity<OrderDtos.OrderResponse> deliver(@PathVariable String publicRef,
                                                           @Valid @RequestBody DeliverRequest request,
                                                           @CurrentAccount AccountPrincipal staff) {
        OrderEntity order = orders.requireAny(publicRef);
        OrderEntity delivered = results.deliver(order, request.result(), request.reason(), staffOf(staff));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(mapper.toAdminResponse(delivered, orders.timeline(delivered.getId()),
                        vault.status(delivered.getId()).present()));
    }

    @PutMapping
    @Operation(summary = "Correct Wins Achieved, set the method (admin) or add a note, until settled")
    public ResponseEntity<ResultDtos.AdminView> update(@PathVariable String publicRef,
                                                       @Valid @RequestBody ResultDtos.ResultUpdate request,
                                                       @CurrentAccount AccountPrincipal staff) {
        OrderEntity order = orders.requireAny(publicRef);
        results.update(order, request, staffOf(staff));
        return ok(results.adminView(order));
    }

    @PostMapping("/paid")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Confirm the refund has actually been sent: marks it Paid (admin)")
    public ResponseEntity<ResultDtos.AdminView> paid(@PathVariable String publicRef,
                                                     @Valid @RequestBody ResultDtos.PaidRequest request,
                                                     @CurrentAccount AccountPrincipal admin) {
        OrderEntity order = orders.requireAny(publicRef);
        results.markPaid(order, request, staffOf(admin));
        return ok(results.adminView(order));
    }

    @PostMapping("/issue")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add the store credit to the customer's account: marks it Issued (admin)")
    public ResponseEntity<ResultDtos.AdminView> issue(@PathVariable String publicRef,
                                                      @RequestBody ResultDtos.IssueRequest request,
                                                      @CurrentAccount AccountPrincipal admin) {
        OrderEntity order = orders.requireAny(publicRef);
        results.issue(order, request, staffOf(admin));
        return ok(results.adminView(order));
    }

    @PostMapping(value = "/proof", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Attach the settlement screenshot: JPG or PNG, 5 MB at most (admin)")
    public ResponseEntity<ResultDtos.AdminView> proof(@PathVariable String publicRef,
                                                      @RequestPart("file") MultipartFile file,
                                                      @CurrentAccount AccountPrincipal admin) {
        OrderEntity order = orders.requireAny(publicRef);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiExceptions.BadRequestException("That upload could not be read.");
        }
        results.attachProof(order, bytes, staffOf(admin));
        return ok(results.adminView(order));
    }

    @GetMapping("/proof")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "The settlement screenshot (admin)")
    public ResponseEntity<byte[]> proofImage(@PathVariable String publicRef) {
        OrderEntity order = orders.requireAny(publicRef);
        SettlementProofEntity proof = results.proof(order)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No screenshot is attached."));
        return ResponseEntity.ok()
                // The type the server decided from the bytes, never what the browser said.
                .contentType(MediaType.parseMediaType(proof.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"settlement-" + order.getPublicRef()
                        + ("image/png".equals(proof.getContentType()) ? ".png" : ".jpg") + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(proof.getBytes());
    }

    private static OrderResultService.Staff staffOf(AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        return new OrderResultService.Staff(principal.id(), principal.publicId(), principal.role() == AccountRole.ADMIN);
    }

    private static ResponseEntity<ResultDtos.AdminView> ok(ResultDtos.AdminView view) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(view);
    }
}
