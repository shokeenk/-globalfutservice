package com.globalfutservice.admin;

import java.util.List;

import com.globalfutservice.popups.PopupPublicService;
import com.globalfutservice.popups.PopupService;
import com.globalfutservice.popups.PopupService.Change;
import com.globalfutservice.popups.PopupService.PopupInput;
import com.globalfutservice.popups.PopupService.PopupView;
import com.globalfutservice.popups.PopupTypes;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The Popup Manager. Admins only; every change is logged by {@link PopupService}. */
@RestController
@RequestMapping("/api/v1/admin/popups")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin")
public class AdminPopupController {

    private final PopupService popups;
    private final PopupPublicService live;

    public AdminPopupController(PopupService popups, PopupPublicService live) {
        this.popups = popups;
        this.live = live;
    }

    public record EnabledRequest(boolean enabled) {
    }

    /** What the editor offers: the pages and the named steps the storefront fires. */
    public record Options(List<PopupTypes.Page> pages, List<String> steps) {
    }

    @GetMapping
    @Operation(summary = "Every popup, with status, schedule and stats")
    public ResponseEntity<List<PopupView>> list() {
        return ok(popups.list());
    }

    @GetMapping("/options")
    @Operation(summary = "The pages and page steps a popup can target")
    public ResponseEntity<Options> options() {
        return ok(new Options(List.of(PopupTypes.Page.values()), PopupTypes.KNOWN_STEPS));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PopupView> get(@PathVariable String id) {
        return ok(popups.get(id));
    }

    @GetMapping("/{id}/changes")
    @Operation(summary = "Who changed a popup, when, and what they did")
    public ResponseEntity<List<Change>> changes(@PathVariable String id) {
        return ok(popups.changes(id));
    }

    @PostMapping
    @Operation(summary = "Create a popup: a draft, switched off")
    public ResponseEntity<PopupView> create(@CurrentAccount AccountPrincipal admin, @RequestBody PopupInput input) {
        return changed(HttpStatus.CREATED, popups.create(input, admin.id()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PopupView> update(@CurrentAccount AccountPrincipal admin, @PathVariable String id,
                                            @RequestBody PopupInput input) {
        return changed(HttpStatus.OK, popups.update(id, input, admin.id()));
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<PopupView> duplicate(@CurrentAccount AccountPrincipal admin, @PathVariable String id) {
        return changed(HttpStatus.CREATED, popups.duplicate(id, admin.id()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentAccount AccountPrincipal admin, @PathVariable String id) {
        popups.delete(id, admin.id());
        live.invalidate();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<PopupView> publish(@CurrentAccount AccountPrincipal admin, @PathVariable String id) {
        return changed(HttpStatus.OK, popups.publish(id, admin.id()));
    }

    @PostMapping("/{id}/unpublish")
    public ResponseEntity<PopupView> unpublish(@CurrentAccount AccountPrincipal admin, @PathVariable String id) {
        return changed(HttpStatus.OK, popups.unpublish(id, admin.id()));
    }

    @PutMapping("/{id}/enabled")
    @Operation(summary = "Switch a popup on or off")
    public ResponseEntity<PopupView> enabled(@CurrentAccount AccountPrincipal admin, @PathVariable String id,
                                             @RequestBody EnabledRequest request) {
        return changed(HttpStatus.OK, popups.setEnabled(id, request.enabled(), admin.id()));
    }

    @PostMapping(path = "/{id}/images/{slot}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload or replace the desktop or mobile image: JPG, PNG or WebP, at most 1.5 MB")
    public ResponseEntity<PopupView> image(@CurrentAccount AccountPrincipal admin, @PathVariable String id,
                                           @PathVariable String slot, @RequestParam("file") MultipartFile file) {
        return changed(HttpStatus.OK, popups.putImage(id, mobileSlot(slot), file, admin.id()));
    }

    @DeleteMapping("/{id}/images/mobile")
    public ResponseEntity<PopupView> removeMobile(@CurrentAccount AccountPrincipal admin, @PathVariable String id) {
        return changed(HttpStatus.OK, popups.removeMobileImage(id, admin.id()));
    }

    private static boolean mobileSlot(String slot) {
        return switch (slot) {
            case "mobile" -> true;
            case "desktop" -> false;
            default -> throw new com.globalfutservice.web.ApiExceptions.NotFoundException("No such image slot.");
        };
    }

    private <T> ResponseEntity<T> changed(HttpStatus status, T body) {
        live.invalidate();
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
