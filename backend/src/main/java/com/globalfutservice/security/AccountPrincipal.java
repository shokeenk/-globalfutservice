package com.globalfutservice.security;

import com.globalfutservice.identity.AccountRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated caller, as carried on the security context.
 *
 * <p>Holds the database id because every ownership check in this application is done in
 * the query — {@code findByPublicRefAndAccountId(...)} — rather than by fetching a row
 * and comparing afterwards. Broken object-level authorisation is the most common serious
 * API vulnerability, and the reliable defence is to make it impossible to load somebody
 * else's row in the first place.
 */
public record AccountPrincipal(Long id, String publicId, String email, AccountRole role) {

    public Collection<? extends GrantedAuthority> authorities() {
        if (role == AccountRole.ADMIN) {
            // ADMIN implies OPERATOR; granting both keeps every check a simple hasRole.
            return List.of(
                    new SimpleGrantedAuthority(AccountRole.ADMIN.authority()),
                    new SimpleGrantedAuthority(AccountRole.OPERATOR.authority()));
        }
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    public boolean isStaff() {
        return role.isStaff();
    }
}
