package com.globalfutservice.pricing.web;

import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quotes")
@Tag(name = "Quotes", description = "Server-authoritative pricing")
public class QuoteController {

    private final QuoteService quoteService;
    private final AccountRepository accounts;

    public QuoteController(QuoteService quoteService, AccountRepository accounts) {
        this.quoteService = quoteService;
        this.accounts = accounts;
    }

    @PostMapping
    @Operation(summary = "Price an order",
            description = """
                    Send intent — service, platform, amount — and receive a signed price.

                    The response is valid for a few minutes and must be posted back
                    unmodified to create an order. Prices are never accepted from the
                    client; a quote whose signature or expiry does not check out is
                    refused with 409 and the caller re-quotes.
                    """)
    public ResponseEntity<QuoteDtos.SignedQuote> quote(
            @Valid @RequestBody QuoteDtos.QuoteRequest request,
            @CurrentAccount AccountPrincipal principal) {

        AccountEntity account = principal == null
                ? null
                : accounts.findById(principal.id()).orElse(null);

        QuoteDtos.SignedQuote quote = quoteService.quote(request, account);
        return ResponseEntity.ok()
                // A price is per-customer and short-lived. Nothing may cache it.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(quote);
    }
}
