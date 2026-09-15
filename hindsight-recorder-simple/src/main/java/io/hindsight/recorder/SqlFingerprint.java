package io.hindsight.recorder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 질의 «모양»에 붙이는 지문. 같은 모양이면 같은 지문이 나온다.
 *
 * <p>요약층이 이 값으로 질의를 접고, 재생이 「고친 뒤에 없던 질의가 생겼나」를 볼 때도
 * 이 값으로 비교한다. 🔴 <b>두 자리가 같은 방식으로 계산해야</b> 비교가 성립하므로
 * 계산은 여기 한 곳에만 둔다.
 *
 * <p>SHA-256 앞 8바이트만 쓴다. 충돌을 막는 것이 목적이 아니라 <b>사람이 로그에서 눈으로
 * 짝을 맞출 수 있는</b> 길이를 고른 것이다. 보안 용도가 아니다.
 */
public final class SqlFingerprint {

    private SqlFingerprint() {}

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
}
