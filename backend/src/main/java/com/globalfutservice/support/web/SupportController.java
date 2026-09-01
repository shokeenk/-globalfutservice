package com.globalfutservice.support.web;

import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.support.SupportTicketEntity;
import com.globalfutservice.support.SupportTicketRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/support")
@Tag(name = "Support", description = "Contact form")
public class SupportController {

    private final SupportTicketRepository tickets;

    public SupportController(SupportTicketRepository tickets) {
        this.tickets = tickets;
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
            boolean confirmedNoCredentials) {
    }

    public record TicketResponse(String ref, String message) {
    }

    @PostMapping("/tickets")
    @Operation(summary = "Raise a support ticket",
            description = "Open to guests. Never send account passwords through this form.")
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody TicketRequest request,
                                                 @CurrentAccount AccountPrincipal principal) {
        String ref = "TKT-" + SecureIds.orderRef("26").substring(7);
        SupportTicketEntity ticket = new SupportTicketEntity(
                ref,
                principal == null ? null : principal.id(),
                request.orderRef(),
                request.email().trim(),
                request.subject().trim(),
                request.message().trim());
        tickets.save(ticket);

        return ResponseEntity.status(HttpStatus.CREATED).body(new TicketResponse(
                ref, "Thanks — we have your message. Quote " + ref + " if you follow up."));
    }

    /** Kept for the admin console's resolve action. */
    static void resolve(SupportTicketEntity ticket) {
        ticket.setStatus("ANSWERED");
        ticket.setResolvedAt(Instant.now());
    }
}
