package com.globalfutservice.storecredit;

import com.globalfutservice.domain.money.Currency;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreCreditRepository extends JpaRepository<StoreCreditEntryEntity, Long> {

    @Query("select coalesce(sum(e.amountMinor), 0) from StoreCreditEntryEntity e "
            + "where e.accountId = :accountId and e.currency = :currency")
    long balanceOf(@Param("accountId") Long accountId, @Param("currency") Currency currency);

    /** Every currency the customer has ever held credit in, with what is left in each. */
    @Query("select e.currency as currency, coalesce(sum(e.amountMinor), 0) as minor from StoreCreditEntryEntity e "
            + "where e.accountId = :accountId group by e.currency order by e.currency")
    List<CurrencyBalance> balancesOf(@Param("accountId") Long accountId);

    interface CurrencyBalance {
        Currency getCurrency();

        long getMinor();
    }

    Page<StoreCreditEntryEntity> findByAccountIdOrderByCreatedAtDescIdDesc(Long accountId, Pageable pageable);

    Optional<StoreCreditEntryEntity> findByOrderIdAndEntryType(Long orderId, StoreCreditEntryEntity.Type type);

    boolean existsByOrderIdAndEntryType(Long orderId, StoreCreditEntryEntity.Type type);

    /**
     * Holds the customer's account row until the transaction ends, so two checkouts spending
     * the same balance at once are taken one after the other and the second sees what the
     * first left.
     *
     * <p>{@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}: it still makes a second spender
     * wait, but not an insert elsewhere whose foreign key points at the account (a
     * notification, a ledger line), which only needs a key-share lock. With {@code FOR
     * UPDATE}, such an insert from another transaction during checkout waited on checkout.
     */
    @Query(value = "select id from account where id = :accountId for no key update", nativeQuery = true)
    Long lockAccount(@Param("accountId") Long accountId);
}
