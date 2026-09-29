package com.globalfutservice.admin;

import com.globalfutservice.fulfilment.FutTransferClient;
import com.globalfutservice.fulfilment.VendorOrderActions;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public AdminVendorOrderController(VendorOrderActions actions, OrderService orderService) {
        this.actions = actions;
        this.orderService = orderService;
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

    private static VendorOrderActions.Admin as(AccountPrincipal admin) {
        return new VendorOrderActions.Admin(admin.id(), admin.email());
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
