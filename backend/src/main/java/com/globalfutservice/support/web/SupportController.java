package com.globalfutservice.support.web;

import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.support.SupportMessageEntity;
import com.globalfutservice.support.SupportService;
import com.globalfutservice.support.SupportTicketEntity;
import com.globalfutservice.support.SupportTicketRepository;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Support for customers: the contact form, their tickets, and answering a reply.
 *
 * <p><b>Who can read a ticket.</b> The customer whose account opened it, signed in; or
 * anybody holding the private key from the email we sent about it, which is how a guest
 * reads and answers. Anyone else gets "no such ticket", the same answer as for a ticket
 * that does not exist, so a reference alone reveals nothing.
 *
 * <p><b>What they can read.</b> Messages, never staff notes: the thread here is read with
 * the kind fixed to MESSAGE.
 */
@RestController
@RequestMapping("/api/v1/support")
@Tag(name = "Support", description = "Contact form and support tickets")
public class SupportController {

    private final SupportTicketRepository tickets;
    private final SupportService support;

    public SupportController(SupportTicketRepository tickets, SupportService support) {
        this.tickets = tickets;
        this.support = support;
    }

    public record TicketRequest(
            @Email(message = "That does not look like an email address")
            @NotBlank(message = "We need an email to reply to")
            String email,

            @Size(max = 32)
            String orderRef,

            @NotBlank(message = "A subject helps us route this")
            @Size(max = 120)
            String subject,

            @NotBlank(message = "Please tell us what is happening")
            @Size(max = 4000, message = "Please keep it under 4000 characters")
            String message,

            /**
             * The one thing worth validating hard on a public contact form.
             *
             * <p>People will paste an EA password into a free-text box if nothing stops
             * them. The acknowledgement is a speed bump; the real control is that this
             * field never reaches the credential vault and the body is stored as ordinary
             * text a support agent reads — so the form says, in as many words, do not put
             * a password here.
             */
            @AssertTrue(message = "Please confirm you have not included your password")
            boolean confirmedNoCredentials,

            /** Coins, Boosting, Coaching, Payment, Account, Technical or Other. Optional. */
            String category) {
    }

    public record TicketResponse(String ref, String message) {
    }

    @PostMapping("/tickets")
    @Operation(summary = "Raise a support ticket",
            description = "Open to guests. Never send account passwords through this form.")
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody TicketRequest request,
                                                 @CurrentAccount AccountPrincipal principal) {
        SupportTicketEntity ticket = support.openFromCustomer(principal == null ? null : principal.id(),
                request.orderRef(), request.email(), request.subject(), request.message(), request.category());
        return ResponseEntity.status(HttpStatus.CREATED).body(new TicketResponse(ticket.getPublicRef(),
                "Thanks — we have your message. Quote " + ticket.getPublicRef() + " if you follow up."));
    }

    public record TicketSummary(String ref, String subject, String category, String status, Instant lastActivityAt) {
    }

    @GetMapping("/tickets")
    @Operation(summary = "Your support tickets, most recent first (signed in)")
    public ResponseEntity<List<TicketSummary>> mine(@CurrentAccount AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Sign in to see your tickets.");
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(
                tickets.findByAccountIdOrderByCreatedAtDesc(principal.id(), PageRequest.of(0, 50)).stream()
                        .map(t -> new TicketSummary(t.getPublicRef(), t.getSubject(), t.getCategory(), t.getStatus(),
                                t.getLastActivityAt()))
                        .toList());
    }

    /** "SUPPORT" for staff, so no name of a person on the team reaches the customer. */
    public record Message(String from, String body, Instant at) {
    }

    public record Thread(String ref, String subject, String category, String status, String orderRef,
                         Instant createdAt, List<Message> messages) {
    }

    @GetMapping("/tickets/{ref}")
    @Operation(summary = "One of your tickets: signed in, or with the key from our email")
    public ResponseEntity<Thread> one(@PathVariable String ref, @RequestParam(required = false) String key,
                                      @CurrentAccount AccountPrincipal principal) {
        SupportTicketEntity ticket = readable(ref, key, principal);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(thread(ticket));
    }

    public record ReplyRequest(
            @NotBlank(message = "Write a message first")
            @Size(max = 4000, message = "Please keep it under 4000 characters")
            String message) {
    }

    @PostMapping("/tickets/{ref}/messages")
    @Operation(summary = "Answer one of your tickets: signed in, or with the key from our email")
    public ResponseEntity<Thread> reply(@PathVariable String ref, @RequestParam(required = false) String key,
                                        @Valid @RequestBody ReplyRequest request,
                                        @CurrentAccount AccountPrincipal principal) {
        SupportTicketEntity ticket = readable(ref, key, principal);
        support.customerWrite(ticket, request.message());
        return ResponseEntity.ok(thread(ticket));
    }

    private SupportTicketEntity readable(String ref, String key, AccountPrincipal principal) {
        SupportTicketEntity ticket = tickets.findByPublicRef(ref).orElse(null);
        boolean owner = ticket != null && principal != null && ticket.getAccountId() != null
                && Objects.equals(ticket.getAccountId(), principal.id());
        if (ticket == null || !(owner || support.linkKeyMatches(ticket.getPublicRef(), key))) {
            throw new ApiExceptions.NotFoundException("No such ticket.");
        }
        return ticket;
    }

    private Thread thread(SupportTicketEntity ticket) {
        List<Message> messages = support.thread(ticket, false).stream()
                .map(m -> new Message(SupportMessageEntity.STAFF.equals(m.getAuthor()) ? "SUPPORT" : "CUSTOMER",
                        m.getBody(), m.getCreatedAt()))
                .toList();
        return new Thread(ticket.getPublicRef(), ticket.getSubject(), ticket.getCategory(), ticket.getStatus(),
                ticket.getOrderRef(), ticket.getCreatedAt(), messages);
    }
}
