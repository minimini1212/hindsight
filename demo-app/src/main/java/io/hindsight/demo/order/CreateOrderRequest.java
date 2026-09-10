package io.hindsight.demo.order;

/** 주문 생성 요청 본문. 🔬 Filter 가 «본문»을 잡을 수 있는지 재려고 만들었다. */
public record CreateOrderRequest(Long memberId, String product) {}
