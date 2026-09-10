package io.hindsight.demo.order;

/** 화면에 내보내는 주문 한 줄. */
public record OrderView(Long id, String product, String memberName) {}
