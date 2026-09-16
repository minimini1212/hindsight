package io.hindsight.core.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code .env} 파일을 읽어 환경변수처럼 쓰게 해 준다.
 *
 * <h2>왜 필요한가</h2>
 * 자바는 {@code .env} 를 자동으로 안 읽는다. 그런데 이 프로젝트의 규율은
 * <b>비밀값을 {@code .env} 에 둔다</b>이므로, 그걸 읽는 자리가 한 곳은 있어야 한다.
 *
 * <h2>🔴 진짜 환경변수가 «이긴다»</h2>
 * 배포에서는 환경변수로 넣고, 손으로 돌릴 때만 {@code .env} 를 쓴다.
 * 파일이 환경변수를 덮으면 <b>배포에서 설정한 값이 파일 한 줄 때문에 조용히 무시된다.</b>
 *
 * <h2>🔴 값을 절대 찍지 않는다</h2>
 * 이 클래스는 <b>이름만</b> 보여 준다. 한 번 로그에 찍힌 키는 로그가 있는 모든 곳에 남는다.
 */
public final class DotEnv {

    private final Map<String, String> 파일에서_읽은것;

    private DotEnv(Map<String, String> 값들) {
        this.파일에서_읽은것 =값들;
    }

    /**
     * 🔴 <b>지금 자리에서 위로 올라가며 {@code .env} 를 찾는다.</b>
     *
     * <p>2026-09-16 에 {@code demo-app/} 안에서 돌렸더니 「키가 없다」가 나왔다.
     * 키는 저장소 뿌리의 {@code .env} 에 있었다. <b>여러 모듈이 있는 저장소에서는
     * 「지금 자리」가 뿌리가 아닌 것이 보통</b>이라, 못 찾는 쪽이 기본값이 되어서는 안 된다.
     *
     * @return 못 찾아도 «터지지 않는다». 그때는 환경변수만 쓴다
     */
    public static DotEnv 찾아_읽는다() {
        Path 여기 = Path.of("").toAbsolutePath();
        for (Path p = 여기; p != null; p = p.getParent()) {
            Path 후보 = p.resolve(".env");
            if (Files.isRegularFile(후보)) {
                return 읽는다(후보);
            }
        }
        return new DotEnv(Map.of());
    }

    /** 못 읽어도 «터지지 않는다». 파일이 없는 것은 흔한 일이고, 그때는 환경변수만 쓴다. */
    public static DotEnv 읽는다(Path file) {
        Map<String, String> 값들 = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return new DotEnv(값들);
        }
        try {
            List<String> 줄들 = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String 줄 : 줄들) {
                String t = 줄.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String 이름 = t.substring(0, eq).trim();
                String 값 = t.substring(eq + 1).trim();
                // 따옴표로 감싼 값을 흔히 쓴다. 벗겨 준다.
                if (값.length() >= 2 && ((값.startsWith("\"") && 값.endsWith("\""))
                        || (값.startsWith("'") && 값.endsWith("'")))) {
                    값 = 값.substring(1, 값.length() - 1);
                }
                if (!값.isEmpty()) {
                    값들.put(이름, 값);
                }
            }
        } catch (IOException e) {
            // 🔴 못 읽었다고 멈추지 않는다. 다만 「읽었다」고도 하지 않는다 — 값이 비어 있을 뿐이다.
            return new DotEnv(Map.of());
        }
        return new DotEnv(값들);
    }

    /** 🔴 환경변수 → {@code .env} 순서. 환경변수가 이긴다. */
    public Function<String, String> 조회() {
        return 이름 -> {
            String 진짜 = System.getenv(이름);
            if (진짜 != null && !진짜.isBlank()) {
                return 진짜;
            }
            return 파일에서_읽은것.get(이름);
        };
    }

    /** 🔴 <b>이름만</b> 돌려준다. 값은 절대 안 준다. */
    public List<String> 파일에서_읽은_이름들() {
        return List.copyOf(파일에서_읽은것.keySet());
    }
}
