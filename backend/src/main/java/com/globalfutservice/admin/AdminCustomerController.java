package com.globalfutservice.admin;

import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Customers: accounts and guests, their orders, and what they have spent.
 *
 * <p>Staff read it, because looking a customer up is support work. <b>What a customer has
 * spent is an admin's</b>, like every other money total: it is left out of the responses
 * an operator gets rather than hidden in their screen. Renaming an account is an admin's
 * too; a guest has no account to rename.
 */
@RestController
@RequestMapping("/api/v1/admin/customers")
@Tag(name = "Admin — customers", description = "Accounts and guests")
public class AdminCustomerController {

    private static final Logger log = LoggerFactory.getLogger(AdminCustomerController.class);

    private final AdminCustomerQueries queries;
    private final AccountRepository accounts;

    public AdminCustomerController(AdminCustomerQueries queries, AccountRepository accounts) {
        this.queries = queries;
        this.accounts = accounts;
    }

    private static boolean admin(AccountPrincipal me) {
        return me != null && me.role() == AccountRole.ADMIN;
    }

    @GetMapping
    @Operation(summary = "Customers, most recent order first",
            description = "search: name, email, EA ID or Discord name/ID. filter: all, with, without "
                    + "(orders). Spent totals are included for admins only.")
    public ResponseEntity<AdminCustomerQueries.Page> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @CurrentAccount AccountPrincipal me) {
        if (search != null && search.trim().length() > AdminOrderFilter.MAX_SEARCH) {
            throw new ApiExceptions.BadRequestException("That search is too long.");
        }
        AdminCustomerQueries.Filter parsed = switch (filter.trim().toLowerCase(Locale.ROOT)) {
            case "all" -> AdminCustomerQueries.Filter.ALL;
            case "with" -> AdminCustomerQueries.Filter.WITH_ORDERS;
            case "without" -> AdminCustomerQueries.Filter.WITHOUT_ORDERS;
            default -> throw new ApiExceptions.BadRequestException("Unknown customer filter.");
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(queries.search(search, parsed, Math.max(0, page), Math.max(1, Math.min(size, 100)), admin(me)));
    }

    @GetMapping("/overview")
    @Operation(summary = "Customer counts: total, new this month, with orders")
    public ResponseEntity<AdminCustomerQueries.Overview> overview() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(queries.overview());
    }

    @GetMapping("/{key}")
    @Operation(summary = "One customer, with their latest orders")
    public ResponseEntity<AdminCustomerQueries.Detail> one(@PathVariable String key,
                                                          @CurrentAccount AccountPrincipal me) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(queries.detail(key, admin(me))
                        .orElseThrow(() -> new ApiExceptions.NotFoundException("No such customer.")));
    }

    public record RenameRequest(
            @NotBlank(message = "A name is required")
            @Size(max = 80, message = "Keep the name under 80 characters")
            String name) {
    }

    /**
     * Changes the name on a customer account. The only thing editable here: the email is
     * how they sign in, and EA ID and platform belong to each order, not the person.
     */
    @PatchMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @Operation(summary = "Rename a customer account (admin)")
    public ResponseEntity<AdminCustomerQueries.Detail> rename(@PathVariable String key,
                                                             @Valid @RequestBody RenameRequest request,
                                                             @CurrentAccount AccountPrincipal me) {
        if (key.startsWith("g-")) {
            throw new ApiExceptions.ConflictException("guest",
                    "A guest has no account, so there is no name to change.");
        }
        AccountEntity account = key.startsWith("a-")
                ? accounts.findByPublicId(key.substring(2))
                        .filter(a -> a.getRole() == AccountRole.CUSTOMER).orElse(null)
                : null;
        if (account == null) {
            throw new ApiExceptions.NotFoundException("No such customer.");
        }
        account.setDisplayName(request.name().trim());
        accounts.saveAndFlush(account);
        log.info("Admin {} renamed customer account {}", me.publicId(), account.getPublicId());
        return ResponseEntity.ok(queries.detail(key, true)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such customer.")));
    }
}
