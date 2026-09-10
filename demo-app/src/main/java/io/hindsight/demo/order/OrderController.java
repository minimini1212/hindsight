package io.hindsight.demo.order;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 주문 목록. 🔴 이 경로가 N+1 을 일으킨다 (OrderService#findAll). */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<OrderView> list() {
        return orderService.findAll();
    }

    /** 🔬 본문이 있는 요청. Filter 가 본문을 잡고도 앱이 정상인지 재는 데 쓴다. */
    @PostMapping
    public OrderView create(@RequestBody CreateOrderRequest request) {
        return orderService.create(request);
    }
}
