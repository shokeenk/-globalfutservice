package com.globalfutservice.admin;

import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.ResumePaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the customer is paying for one order: the method they are on now, what it charges,
 * and every invoice and claim, superseded Payop invoices included. Read-only.
 *
 * <p>Staff only, as everything under {@code /api/v1/admin} is (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/admin/orders/{publicRef}/payment")
@Tag(name = "Admin: order payment")
public class AdminOrderPaymentController {

    private final OrderService orders;
    private final ResumePaymentService payments;

    public AdminOrderPaymentController(OrderService orders, ResumePaymentService payments) {
        this.orders = orders;
        this.payments = payments;
    }

    @GetMapping
    @Operation(summary = "The current payment method, its fee breakdown, and every payment attempt on the order")
    public ResponseEntity<ResumePaymentService.StaffView> payment(@PathVariable String publicRef) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(payments.staffView(orders.requireAny(publicRef)));
    }
}
