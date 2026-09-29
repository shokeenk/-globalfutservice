package com.globalfutservice.orders.web;

import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.orders.OrderEventEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the customer's timeline says about who moved their order, and why. */
class CustomerTimelineTest {

    private static OrderEventEntity event(Actor actor, String label, String reason) {
        return new OrderEventEntity(1L, OrderStatus.IN_PROGRESS, OrderStatus.ON_HOLD, actor, null, label, reason);
    }

    @Test
    @DisplayName("a row the first supplier poll wrote keeps its status change, loses the partner's name and codes")
    void legacySupplierRow() {
        OrderDtos.OrderEventDto dto = OrderMapper.toCustomerEventDto(
                event(Actor.SYSTEM, "futtransfer", "Supplier reports interrupted — NEW_BACKUP_CODES"));

        assertThat(dto.fromStatus()).isEqualTo("IN_PROGRESS");
        assertThat(dto.toStatus()).isEqualTo("ON_HOLD");
        assertThat(dto.actorLabel()).isEqualTo("GFS");
        assertThat(dto.reason()).isNull();
    }

    @Test
    @DisplayName("every system row is signed GFS, and a reason written for the customer is kept")
    void systemRows() {
        String sentence = "Your EA sign-in was not accepted. Please enter your details again so we can start.";
        assertThat(OrderMapper.toCustomerEventDto(event(Actor.SYSTEM, "futtransfer", sentence)))
                .satisfies(d -> {
                    assertThat(d.actorLabel()).isEqualTo("GFS");
                    assertThat(d.reason()).isEqualTo(sentence);
                });
        assertThat(OrderMapper.toCustomerEventDto(event(Actor.SYSTEM, "gateway", "Payment captured")).actorLabel())
                .isEqualTo("GFS");
    }

    @Test
    @DisplayName("rows written by people are shown as written")
    void peopleRows() {
        OrderDtos.OrderEventDto dto = OrderMapper.toCustomerEventDto(event(Actor.OPERATOR, "Vinay", "Checked the club"));

        assertThat(dto.actorLabel()).isEqualTo("Vinay");
        assertThat(dto.reason()).isEqualTo("Checked the club");
    }
}
