package com.globalfutservice.marketing;

import com.globalfutservice.identity.AccountEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Who may be sent a promotion.
 *
 * <p>Every query here begins with {@code marketingOptIn = true}. That is not a filter the
 * caller supplies — it is built into the only way of asking, so no call site can forget it
 * and no future segment can be added without it.
 *
 * <p>Separate from AccountRepository so the identity package does not acquire marketing
 * concerns, and so the consent rule lives in one file somebody can read end to end.
 */
public interface MarketingAudienceRepository extends Repository<AccountEntity, Long> {

    /** Everyone who has opted in and has an address to send to. */
    @Query("""
            select a from AccountEntity a
             where a.marketingOptIn = true
               and a.email is not null and a.email <> ''
             order by a.id
            """)
    List<AccountEntity> optedIn();

    /**
     * Opted-in customers who have previously bought one of these SKUs.
     *
     * <p>Joined through orders on account_id, so guest checkouts are excluded — there is
     * no account to hold consent against, and an address that arrived on a guest order was
     * given to complete that order, not to receive marketing.
     */
    @Query("""
            select distinct a from AccountEntity a
             where a.marketingOptIn = true
               and a.email is not null and a.email <> ''
               and exists (select 1 from OrderEntity o
                            where o.accountId = a.id
                              and o.sku in :skus)
             order by a.id
            """)
    List<AccountEntity> optedInWhoBought(@Param("skus") List<com.globalfutservice.domain.catalog.Sku> skus);
}
