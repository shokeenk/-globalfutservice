package com.globalfutservice.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Saved views: a person's own named filters on a console page.
 *
 * <p>Staff, not admin — anybody who uses the Orders page can keep shortcuts on it. Every
 * lookup is by the signed-in account, so one person can neither see nor delete another's.
 *
 * <p>The filters are checked against the keys the page actually has, so this cannot
 * become somewhere to park arbitrary JSON. They are never turned into a query here: the
 * page reads a view back and sends its filters to the search endpoint, which parses them
 * like any other request.
 */
@RestController
@RequestMapping("/api/v1/admin/saved-views")
@Tag(name = "Admin — saved views", description = "Per-person filter shortcuts")
public class AdminSavedViewController {

    static final Set<String> PAGES = Set.of("orders");
    static final Set<String> FILTER_KEYS =
            Set.of("service", "status", "platform", "from", "to", "search", "attention");
    static final int MAX_PER_PAGE = 20;
    static final int MAX_VALUE = 100;

    private static final TypeReference<Map<String, String>> FILTERS = new TypeReference<>() {
    };

    private final SavedViewRepository views;
    private final ObjectMapper json;

    public AdminSavedViewController(SavedViewRepository views, ObjectMapper json) {
        this.views = views;
        this.json = json;
    }

    public record SavedViewDto(Long id, String name, Map<String, String> filters, Instant createdAt) {
    }

    public record CreateRequest(
            @NotBlank String page,
            @NotBlank(message = "Give the view a name")
            @Size(max = 60, message = "Keep the name under 60 characters")
            String name,
            @NotNull Map<String, String> filters) {
    }

    @GetMapping
    @Operation(summary = "Your saved views on one page")
    @Transactional(readOnly = true)
    public ResponseEntity<List<SavedViewDto>> list(@RequestParam String page,
                                                   @CurrentAccount AccountPrincipal me) {
        requirePage(page);
        return ResponseEntity.ok(views.findByAccountIdAndPageOrderByNameAsc(me.id(), page)
                .stream().map(this::toDto).toList());
    }

    @PostMapping
    @Operation(summary = "Save the current filters under a name")
    @Transactional
    public ResponseEntity<SavedViewDto> create(@Valid @RequestBody CreateRequest request,
                                               @CurrentAccount AccountPrincipal me) {
        requirePage(request.page());
        String name = request.name().trim();
        Map<String, String> filters = checked(request.filters());

        if (views.countByAccountIdAndPage(me.id(), request.page()) >= MAX_PER_PAGE) {
            throw new ApiExceptions.ConflictException("too_many_views",
                    "You have " + MAX_PER_PAGE + " saved views here already. Delete one first.");
        }
        if (views.existsByAccountIdAndPageAndNameIgnoreCase(me.id(), request.page(), name)) {
            throw new ApiExceptions.ConflictException("name_taken",
                    "You already have a view called \"" + name + "\".");
        }
        try {
            SavedViewEntity saved = views.saveAndFlush(new SavedViewEntity(
                    me.id(), request.page(), name, json.writeValueAsString(filters)));
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
        } catch (DataIntegrityViolationException raced) {
            // Two saves under the same name at once: the unique index picked one.
            throw new ApiExceptions.ConflictException("name_taken",
                    "You already have a view called \"" + name + "\".");
        } catch (JsonProcessingException e) {
            throw new ApiExceptions.BadRequestException("Those filters could not be saved.");
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete one of your saved views")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id, @CurrentAccount AccountPrincipal me) {
        SavedViewEntity view = views.findByIdAndAccountId(id, me.id())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such saved view."));
        views.delete(view);
        return ResponseEntity.noContent().build();
    }

    private static void requirePage(String page) {
        if (!PAGES.contains(page)) {
            throw new ApiExceptions.BadRequestException("Unknown page.");
        }
    }

    /** Known keys, short values, blanks dropped. Sorted so the stored JSON is stable. */
    static Map<String, String> checked(Map<String, String> filters) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            if (!FILTER_KEYS.contains(entry.getKey())) {
                throw new ApiExceptions.BadRequestException("Unknown filter: " + entry.getKey());
            }
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.length() > MAX_VALUE) {
                throw new ApiExceptions.BadRequestException("A filter value is too long.");
            }
            out.put(entry.getKey(), value.trim());
        }
        return out;
    }

    private SavedViewDto toDto(SavedViewEntity view) {
        Map<String, String> filters;
        try {
            filters = json.readValue(view.getFilters(), FILTERS);
        } catch (JsonProcessingException e) {
            // Written by this class, so unreadable means edited by hand. Show it empty
            // rather than failing the whole list.
            filters = Map.of();
        }
        return new SavedViewDto(view.getId(), view.getName(), filters, view.getCreatedAt());
    }
}
