package io.hindsight.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 질의에서 값을 지워 «모양»으로 만들고, 그 모양에 지문을 붙인다.
 *
 * <h2>🔴 이 계산이 «모델» 쪽에 있는 이유 — 두 곳이 같은 답을 내야 한다</h2>
 * 이 값을 쓰는 자리가 둘이고, 그 둘은 <b>서로 대조된다.</b>
 *
 * <pre>
 *   기록할 때   기록기가 질의를 모양으로 접어 요약층에 넣는다   (관측 대상 앱 «안»)
 *                                  ↕  대조
 *   재생할 때   재생이 「기록에 없는 질의가 생겼나」를 본다      (우리 쪽 «밖»)
 * </pre>
 *
 * 🔴 <b>두 자리가 조금이라도 다르게 계산하면 대조가 «영영» 안 맞는다.</b> 그리고 그 고장은
 * 조용하다 — 오류가 나는 게 아니라 <b>모든 질의가 「기록에 없는 것」으로 보여서</b>
 * 모든 재생이 채점 불가가 된다. 원인을 찾기까지 한참 걸린다.
 *
 * <p>그래서 계산을 한 곳에만 두고, 그 한 곳은 <b>안팎이 같이 쓰는 유일한 모듈</b>인
 * 여기여야 한다. {@code hindsight-core} 에 두면 관측 대상 앱 안에서 도는 코드가 못 쓰고,
 * 기록기 쪽에 두면 우리 쪽 재생이 못 쓴다.
 *
 * <h2>의존성은 여전히 0 이다</h2>
 * 정규식과 SHA-256 은 JDK 가 주는 것이라, 이 클래스가 들어와도
 * 「관측 대상 앱에 라이브러리를 하나도 안 끌고 들어간다」는 약속은 그대로다.
 */
public final class SqlShapes {

    private SqlShapes() {}

    /** SQL 을 못 알아냈을 때 쓰는 모양. 🔴 빈 문자열이 아니다 — 「없었다」가 아니라 「모른다」다. */
    public static final String UNKNOWN = "(알 수 없음)";

    /**
     * 값이 다른 같은 질의를 한 모양으로 접는다.
     *
     * <h2>🔴 값을 지우는 것이 요점이다</h2>
     * {@code where id = 7} 과 {@code where id = 8} 은 <b>같은 코드가 낸 같은 질의</b>다.
     * 이걸 다른 것으로 세면 N+1 이 「서로 다른 질의 200개」로 보여서 <b>안 잡힌다.</b>
     *
     * <p>2026-09-11 실측에서 「총 횟수」보다 「모양별 반복」이 훨씬 강한 신호라는 것이 나왔고
     * (주문 20건 → 한 모양이 20번), 그래서 오라클이 이 값을 쓴다.
     */
    public static String normalize(String sql) {
        if (sql == null) {
            return UNKNOWN;
        }
        return sql
                .replaceAll("'[^']*'", "?")      // 문자열 값
                .replaceAll("\\b\\d+\\b", "?")   // 숫자 값
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 모양에 붙이는 지문. 같은 모양이면 같은 값이 나온다.
     *
     * <p>SHA-256 앞 8바이트만 쓴다. 충돌을 막는 것이 목적이 아니라 <b>사람이 로그에서 눈으로
     * 짝을 맞출 수 있는</b> 길이를 고른 것이다. 보안 용도가 아니다.
     *
     * @param normalizedSql {@link #normalize} 를 «이미 거친» 문자열
     */
    public static String hash(String normalizedSql) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] full = digest.digest(normalizedSql.getBytes(StandardCharsets.UTF_8));
            byte[] head = new byte[8];
            System.arraycopy(full, 0, head, 0, 8);
            return HexFormat.of().formatHex(head);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 가 반드시 제공한다. 여기 오면 JDK 가 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 못 찾았다", e);
        }
    }

    /** 원본 SQL 하나를 모양과 지문으로. 두 단계를 따로 부르다 순서를 틀리지 않게 묶어 둔다. */
    public static Shape of(String sql) {
        String normalized = normalize(sql);
        return new Shape(normalized, hash(normalized));
    }

    /** 질의 하나의 모양과 그 지문. */
    public record Shape(String normalized, String hash) {}
}
