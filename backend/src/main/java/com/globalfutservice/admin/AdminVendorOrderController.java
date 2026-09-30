package com.globalfutservice.admin;

import com.globalfutservice.fulfilment.FutTransferClient;
import com.globalfutservice.fulfilment.VendorCallLog;
import com.globalfutservice.fulfilment.VendorOrderActions;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One order at FUT Transfer: the admin actions on the order the vendor already has.
 *
 * <p>Admin only, like every action that reaches the vendor. None of them can create an
 * order there; each is recorded with who did it.
 */
@RestController
@RequestMapping("/api/v1/admin/orders/{publicRef}/vendor")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin — fulfilment partner", description = "FUT Transfer controls")
public class AdminVendorOrderController {

    private final VendorOrderActions actions;
    private final OrderService orderService;
    private final VendorCallLog calls;
    private final AccountRepository accounts;

    public AdminVendorOrderController(VendorOrderActions actions, OrderService orderService, VendorCallLog calls,
                                      AccountRepository accounts) {
        this.actions = actions;
        this.orderService = orderService;
        this.calls = calls;
        this.accounts = accounts;
    }

    /** The admin has checked the partner's dashboard and there is no order there. */
    public record RetryRequest(boolean confirmedAbsent) {
    }

    /** The partner's order id, from its dashboard; optional when the order can be found by our reference. */
    public record LinkRequest(String vendorOrderId) {
    }

    /** How it was settled: refunded, delivered by other means, given up. */
    public record ResolveRequest(String note) {
    }

    @GetMapping
    @Operation(summary = "The order at FUT Transfer: its state, every call, every admin action, and what can be done now")
    public ResponseEntity<VendorOrderActions.Section> section(@PathVariable String publicRef) {
        OrderEntity order = orderService.requireAny(publicRef);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(actions.section(order, calls.forOrder(publicRef)));
    }

    /** What an action came to, when the vendor did it. The page reloads the order to show the rest. */
    public record ActionResponse(String status, String message) {
    }

    @PostMapping("/send-sign-in")
    @Operation(summary = "Send the customer's corrected sign-in to the order FUT Transfer is holding, and restart it")
    public ResponseEntity<ActionResponse> sendSignIn(@PathVariable String publicRef,
                                                     @CurrentAccount AccountPrincipal admin) {
        OrderEntity order = orderService.requireAny(publicRef);
        return answer(actions.sendCorrectedSignIn(order, as(admin)));
    }

    @PostMapping("/resume")
    @Operation(summary = "Restart an order FUT Transfer interrupted, once what stopped it is fixed")
    public ResponseEntity<ActionResponse> resume(@PathVariable String publicRef,
                                                 @CurrentAccount AccountPrincipal admin) {
        return answer(actions.resume(orderService.requireAny(publicRef), as(admin)));
    }

    @PostMapping("/stop")
    @Operation(summary = "Stop the order at FUT Transfer; what it has delivered stays delivered")
    public ResponseEntity<ActionResponse> stop(@PathVariable String publicRef,
                                               @CurrentAccount AccountPrincipal admin) {
        return answer(actions.stop(orderService.requireAny(publicRef), as(admin)));
    }

    @PostMapping("/mark-finished")
    @Operation(summary = "Close the order at FUT Transfer. Never automatic")
    public ResponseEntity<ActionResponse> markFinished(@PathVariable String publicRef,
                                                       @CurrentAccount AccountPrincipal admin) {
        return answer(actions.markFinished(orderService.requireAny(publicRef), as(admin)));
    }

    @PostMapping("/retry")
    @Operation(summary = "Clear an order under review to be approved again, once it is confirmed nothing was created")
    public ResponseEntity<ActionResponse> retry(@PathVariable String publicRef,
                                                @RequestBody(required = false) RetryRequest body,
                                                @CurrentAccount AccountPrincipal admin) {
        return answer(actions.allowResend(orderService.requireAny(publicRef), as(admin),
                body != null && body.confirmedAbsent()));
    }

    @PostMapping("/link")
    @Operation(summary = "Watch an order under review again, once FUT Transfer confirms it has it")
    public ResponseEntity<ActionResponse> link(@PathVariable String publicRef,
                                               @RequestBody(required = false) LinkRequest body,
                                               @CurrentAccount AccountPrincipal admin) {
        return answer(actions.link(orderService.requireAny(publicRef), as(admin),
                body == null ? null : body.vendorOrderId()));
    }

    @PostMapping("/resolve")
    @Operation(summary = "Close an order under review by hand, with a note")
    public ResponseEntity<ActionResponse> resolve(@PathVariable String publicRef,
                                                  @RequestBody(required = false) ResolveRequest body,
                                                  @CurrentAccount AccountPrincipal admin) {
        return answer(actions.resolve(orderService.requireAny(publicRef), as(admin),
                body == null ? null : body.note()));
    }

    /**
     * Who is acting, by name as well as id. The access token carries no email, so it is read
     * from the account: an audit row that says only "account 14" is one nobody reads. The
     * order's timeline gets the opaque public id instead, as every other transition does,
     * because the customer's own API returns that timeline.
     */
    private VendorOrderActions.Admin as(AccountPrincipal admin) {
        String label = admin.email() != null ? admin.email()
                : accounts.findById(admin.id()).map(AccountEntity::getEmail).orElse("account " + admin.id());
        return new VendorOrderActions.Admin(admin.id(), label, admin.publicId());
    }

    /**
     * 200 with the vendor's confirmation. Otherwise the reason, as an error: 409 when it
     * was not sent, 502 when the vendor refused it or its answer was lost.
     */
    private static ResponseEntity<ActionResponse> answer(VendorOrderActions.Result result) {
        return switch (result.status()) {
            case DONE -> ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new ActionResponse(result.status().name(), result.message()));
            case NOT_SENT -> throw new ApiExceptions.ConflictException("vendor_action_not_sent", result.message());
            case REFUSED, UNCERTAIN -> throw new FutTransferClient.FutTransferException(result.message());
        };
    }
}
