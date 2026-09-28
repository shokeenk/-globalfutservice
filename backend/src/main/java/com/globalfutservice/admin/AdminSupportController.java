package com.globalfutservice.admin;

import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.support.SupportMessageEntity;
import com.globalfutservice.support.SupportService;
import com.globalfutservice.support.SupportTicketEntity;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Support page: every ticket, its thread, replies, notes, and closing.
 *
 * <p>Staff, like the order queue: answering customers is the job. Notes stay here; the
 * customer's endpoints never read them. A reply is emailed to the customer with a private
 * link to the conversation, and appears on their bell if they have an account.
 *
 * <p>Statuses as the page names them: Open (waiting for staff), Waiting (for the customer)
 * and Resolved. In the database they are OPEN, ANSWERED and CLOSED, as they always were.
 */
@RestController
@RequestMapping("/api/v1/admin/support")
@Tag(name = "Admin — support", description = "Support tickets and replies")
public class AdminSupportController {

    private static final String ROWS = """
            t as (
              select s.id, s.public_ref, s.email, s.subject, s.category, s.status, s.order_ref,
                     s.created_at, s.last_activity_at,
                     coalesce(nullif(trim(a.display_name), ''), nullif(trim(o.guest_name), '')) as name,
                     (select count(*) from support_message m where m.ticket_id = s.id and m.kind = 'MESSAGE') as messages,
                     (select m.author from support_message m where m.ticket_id = s.id and m.kind = 'MESSAGE'
                       order by m.created_at desc, m.id desc limit 1) as last_from
                from support_ticket s
                left join account a on a.id = s.account_id
                left join orders o on o.public_ref = s.order_ref
            )""";

    private static final Map<String, String> TAB_STATUS = Map.of("open", "OPEN", "waiting", "ANSWERED", "resolved", "CLOSED");

    private final NamedParameterJdbcTemplate jdbc;
    private final SupportService support;

    public AdminSupportController(NamedParameterJdbcTemplate jdbc, SupportService support) {
        this.jdbc = jdbc;
        this.support = support;
    }

    public record Row(String ref, String customerName, String email, String category, String subject, String orderRef,
                      String status, long messages, String lastFrom, Instant createdAt,
                      Instant lastActivityAt) {
    }

    public record Page(List<Row> items, long total, int page, int size) {
    }

    @GetMapping("/tickets")
    @Operation(summary = "Tickets, most recent activity first",
            description = "status: open, waiting or resolved. category: one of the seven. "
                    + "search: reference, customer name or email, subject or order.")
    public ResponseEntity<Page> list(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String search,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> where = new ArrayList<>();
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            String s = TAB_STATUS.get(status.trim().toLowerCase(Locale.ROOT));
            if (s == null) {
                throw new ApiExceptions.BadRequestException("Unknown status filter.");
            }
            params.addValue("status", s);
            where.add("status = :status");
        }
        String c = SupportService.category(category);
        if (c != null) {
            params.addValue("category", c);
            where.add("category = :category");
        }
        if (search != null && !search.isBlank()) {
            if (search.trim().length() > AdminOrderFilter.MAX_SEARCH) {
                throw new ApiExceptions.BadRequestException("That search is too long.");
            }
            params.addValue("p", "%" + AdminOrderSpecs.escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%");
            where.add("""
                    (lower(public_ref) like :p escape '\\' or lower(email) like :p escape '\\'
                     or lower(subject) like :p escape '\\' or lower(coalesce(order_ref, '')) like :p escape '\\'
                     or lower(coalesce(name, '')) like :p escape '\\')""");
        }
        String clause = where.isEmpty() ? " " : " where " + String.join(" and ", where) + " ";
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, 100));
        long total = Optional.ofNullable(jdbc.queryForObject("with " + ROWS + " select count(*) from t" + clause,
                params, Long.class)).orElse(0L);
        params.addValue("limit", s).addValue("offset", (long) p * s);
        List<Row> rows = jdbc.query("with " + ROWS + " select * from t" + clause
                + " order by last_activity_at desc, id desc limit :limit offset :offset", params, (rs, i) -> new Row(
                rs.getString("public_ref"), rs.getString("name"), rs.getString("email"), rs.getString("category"),
                rs.getString("subject"), rs.getString("order_ref"), rs.getString("status"),
                rs.getLong("messages"), rs.getString("last_from"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("last_activity_at"))));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new Page(rows, total, p, s));
    }

    /** Tickets by status, for the cards and tabs. */
    @GetMapping("/overview")
    @Operation(summary = "How many tickets are open, waiting for the customer, and resolved")
    public ResponseEntity<Map<String, Long>> overview() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("open", 0L);
        out.put("waiting", 0L);
        out.put("resolved", 0L);
        jdbc.query("select status, count(*) as n from support_ticket group by status", new MapSqlParameterSource(),
                (RowCallbackHandler) rs -> {
                    String tab = switch (rs.getString("status")) {
                        case "OPEN" -> "open";
                        case "ANSWERED" -> "waiting";
                        default -> "resolved";
                    };
                    out.merge(tab, rs.getLong("n"), Long::sum);
                });
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(out);
    }

    public record Message(long id, String author, String kind, String body, String authorLabel, Instant at) {
    }

    public record Detail(String ref, String status, String category, String subject, String orderRef,
                         Instant createdAt, Instant resolvedAt, String customerName, String email, boolean hasAccount,
                         /** The customer's own link to this ticket, for pasting into a chat with them. */
                         String customerLink,
                         List<Message> messages) {
    }

    @GetMapping("/tickets/{ref}")
    @Operation(summary = "One ticket with its whole thread, notes included")
    public ResponseEntity<Detail> one(@PathVariable String ref) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(detail(support.require(ref)));
    }

    public record WriteRequest(
            @NotBlank(message = "Write a message first")
            @Size(max = 4000, message = "Please keep it under 4000 characters")
            String message,
            /** A note stays with staff; a reply goes to the customer. */
            boolean note) {
    }

    @PostMapping("/tickets/{ref}/messages")
    @Operation(summary = "Reply to the customer, or add a note only staff see")
    public ResponseEntity<Detail> write(@PathVariable String ref, @Valid @RequestBody WriteRequest request,
                                        @CurrentAccount AccountPrincipal staff) {
        SupportTicketEntity ticket = support.require(ref);
        support.staffWrite(ticket, request.message(), request.note(), staff.id(), label(staff));
        return ResponseEntity.ok(detail(ticket));
    }

    @PostMapping("/tickets/{ref}/close")
    @Operation(summary = "Mark a ticket resolved")
    public ResponseEntity<Detail> close(@PathVariable String ref) {
        SupportTicketEntity ticket = support.require(ref);
        support.close(ticket);
        return ResponseEntity.ok(detail(ticket));
    }

    @PostMapping("/tickets/{ref}/reopen")
    @Operation(summary = "Open a resolved ticket again")
    public ResponseEntity<Detail> reopen(@PathVariable String ref) {
        SupportTicketEntity ticket = support.require(ref);
        support.reopen(ticket);
        return ResponseEntity.ok(detail(ticket));
    }

    public record CategoryRequest(String category) {
    }

    @PatchMapping("/tickets/{ref}")
    @Operation(summary = "Set a ticket's category")
    public ResponseEntity<Detail> categorise(@PathVariable String ref, @RequestBody CategoryRequest request) {
        SupportTicketEntity ticket = support.require(ref);
        support.setCategory(ticket, request.category());
        return ResponseEntity.ok(detail(ticket));
    }

    private Detail detail(SupportTicketEntity ticket) {
        String name = jdbc.query("""
                select coalesce(nullif(trim(a.display_name), ''), nullif(trim(o.guest_name), '')) from support_ticket s
                  left join account a on a.id = s.account_id left join orders o on o.public_ref = s.order_ref
                 where s.id = :id
                """, new MapSqlParameterSource("id", ticket.getId()), (rs, i) -> rs.getString(1))
                .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        List<Message> messages = support.thread(ticket, true).stream()
                .map(m -> new Message(m.getId(), m.getAuthor(), m.getKind(), m.getBody(),
                        SupportMessageEntity.STAFF.equals(m.getAuthor()) ? m.getAuthorLabel() : null, m.getCreatedAt()))
                .toList();
        return new Detail(ticket.getPublicRef(), ticket.getStatus(), ticket.getCategory(), ticket.getSubject(),
                ticket.getOrderRef(), ticket.getCreatedAt(), ticket.getResolvedAt(), name,
                ticket.getEmail(), ticket.getAccountId() != null, support.link(ticket.getPublicRef()), messages);
    }

    private static String label(AccountPrincipal staff) {
        return staff.email();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
