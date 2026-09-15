package io.hindsight.recorder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 상한값을 읽는 <b>유일한 자리</b>.
 *
 * <h2>🔴 왜 한 곳에서만 읽나</h2>
 * 값을 쓰는 쪽마다 {@code System.getenv} 를 부르면, 언젠가 한 곳이 기본값을 다르게 기억한다.
 * 그러면 「본문 상한 64KB」가 어떤 자리에서는 64KB 이고 어떤 자리에서는 1MB 가 된다.
 * 어긋난 것을 알아채는 방법이 없다 — <b>둘 다 그럴듯한 값이라 로그로도 안 보인다.</b>
 *
 * <h2>🔴 못 읽으면 멈춘다. 기본값으로 조용히 되돌아가지 않는다</h2>
 * {@code HINDSIGHT_BODY_MAX_BYTES=64kb} 라고 적어 두면(숫자가 아니다) 이 클래스는
 * 예외를 던진다. 조용히 64KB 로 돌아가면 사용자는 자기가 적은 값이 먹은 줄 안다.
 * <b>안 먹은 것을 안 먹었다고 말하지 않는 것</b>이 이 프로젝트가 잡으려는 결함이다.
 *
 * <h2>어디서 온 값인지 같이 들고 다닌다</h2>
 * {@code 30 (기본값)} 과 {@code 30 (환경변수)} 는 다른 사실이다. 앞은 「아무도 안 정했다」,
 * 뒤는 「누군가 30 으로 정했다」. 장애를 볼 때 이 둘을 구별할 수 있어야 한다.
 * {@link #sources()} 가 그것을 돌려준다.
 *
 * <p>🧭 값의 목록과 기본값: {@code docs/DATA_CONTRACT.md} 의 「상한값」 절(§9)
 */
public final class RecorderConfig {

    /** 링 버퍼가 들고 있으려고 «하는» 시간. 실제로 담긴 시간과 다를 수 있고, 둘 다 기록한다. */
    private final int windowSeconds;

    /** 🔴 시간과 바이트 중 «먼저 오는 쪽»이 이긴다. */
    private final long bufferMaxBytes;

    private final int bodyMaxBytes;
    private final int sqlRowsMax;
    private final int sqlRepeatFull;
    private final int capturesPerHour;
    private final long storeMaxBytes;
    private final long latencyTriggerMs;
    private final int recorderErrorLimit;

    /** 🔴 없으면 가명화 대상 필드를 «통째로 버린다». 가짜 키로 대충 넘기지 않는다. */
    private final String pseudonymKey;

    private final Path storeDir;
    private final String appName;

    private final Map<String, String> sources;

    private RecorderConfig(Builder b) {
        this.windowSeconds = b.windowSeconds;
        this.bufferMaxBytes = b.bufferMaxBytes;
        this.bodyMaxBytes = b.bodyMaxBytes;
        this.sqlRowsMax = b.sqlRowsMax;
        this.sqlRepeatFull = b.sqlRepeatFull;
        this.capturesPerHour = b.capturesPerHour;
        this.storeMaxBytes = b.storeMaxBytes;
        this.latencyTriggerMs = b.latencyTriggerMs;
        this.recorderErrorLimit = b.recorderErrorLimit;
        this.pseudonymKey = b.pseudonymKey;
        this.storeDir = b.storeDir;
        this.appName = b.appName;
        this.sources = Map.copyOf(b.sources);
    }

    // ── 읽기 ────────────────────────────────────────────────────────────────

    public int windowSeconds() { return windowSeconds; }
    public long bufferMaxBytes() { return bufferMaxBytes; }
    public int bodyMaxBytes() { return bodyMaxBytes; }
    public int sqlRowsMax() { return sqlRowsMax; }
    public int sqlRepeatFull() { return sqlRepeatFull; }
    public int capturesPerHour() { return capturesPerHour; }
    public long storeMaxBytes() { return storeMaxBytes; }
    public long latencyTriggerMs() { return latencyTriggerMs; }
    public int recorderErrorLimit() { return recorderErrorLimit; }
    public Path storeDir() { return storeDir; }
    public String appName() { return appName; }

    /**
     * 가명화 키. 🔴 {@code null} 은 「키가 없다」이고, 그때는 가명화 대상을 버린다.
     * 빈 문자열로 바꿔 돌려주지 않는다 — 「없다」와 「비었다」는 다른 사실이다.
     */
    public String pseudonymKey() { return pseudonymKey; }

    /** 각 값이 어디서 왔는지. {@code "환경변수"} 또는 {@code "기본값"}. */
    public Map<String, String> sources() { return sources; }

    // ── 만들기 ──────────────────────────────────────────────────────────────

    /** 환경변수에서 읽는다. 값이 없으면 기본값, 값이 이상하면 예외. */
    public static RecorderConfig fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    /**
     * 환경변수를 어디서 읽을지 바꿔 끼울 수 있게 열어 둔다.
     *
     * <p>🔴 테스트를 위해서만 있는 구멍이 아니다. {@code System.getenv} 를 직접 부르는 코드는
     * <b>값이 이상할 때 어떻게 되는지를 시험할 방법이 없다.</b> 시험할 수 없는 분기는
     * 언젠가 틀린 채로 남는다.
     */
    public static RecorderConfig fromEnvironment(Function<String, String> env) {
        Builder b = new Builder();

        b.windowSeconds = b.readInt(env, "HINDSIGHT_WINDOW_SECONDS", 60);
        b.bufferMaxBytes = b.readLong(env, "HINDSIGHT_BUFFER_MAX_BYTES", 32L * 1024 * 1024);
        b.bodyMaxBytes = b.readInt(env, "HINDSIGHT_BODY_MAX_BYTES", 64 * 1024);
        b.sqlRowsMax = b.readInt(env, "HINDSIGHT_SQL_ROWS_MAX", 100);
        b.sqlRepeatFull = b.readInt(env, "HINDSIGHT_SQL_REPEAT_FULL", 3);
        b.capturesPerHour = b.readInt(env, "HINDSIGHT_CAPTURES_PER_HOUR", 20);
        b.storeMaxBytes = b.readLong(env, "HINDSIGHT_STORE_MAX_BYTES", 1024L * 1024 * 1024);
        b.latencyTriggerMs = b.readLong(env, "HINDSIGHT_LATENCY_TRIGGER_MS", 3000);
        b.recorderErrorLimit = b.readInt(env, "HINDSIGHT_AGENT_ERROR_LIMIT", 50);
        b.pseudonymKey = b.readOptional(env, "HINDSIGHT_PSEUDONYM_KEY");
        b.storeDir = Path.of(b.readString(env, "HINDSIGHT_STORE_DIR", "recordings"));
        b.appName = b.readString(env, "HINDSIGHT_APP_NAME", "unknown-app");

        return new RecorderConfig(b);
    }

    /** 테스트가 값을 직접 정할 때. 출처는 전부 {@code "직접 지정"} 이 된다. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int windowSeconds = 60;
        private long bufferMaxBytes = 32L * 1024 * 1024;
        private int bodyMaxBytes = 64 * 1024;
        private int sqlRowsMax = 100;
        private int sqlRepeatFull = 3;
        private int capturesPerHour = 20;
        private long storeMaxBytes = 1024L * 1024 * 1024;
        private long latencyTriggerMs = 3000;
        private int recorderErrorLimit = 50;
        private String pseudonymKey;
        private Path storeDir = Path.of("recordings");
        private String appName = "unknown-app";
        private final Map<String, String> sources = new LinkedHashMap<>();

        public Builder windowSeconds(int v) { this.windowSeconds = v; mark("HINDSIGHT_WINDOW_SECONDS"); return this; }
        public Builder bufferMaxBytes(long v) { this.bufferMaxBytes = v; mark("HINDSIGHT_BUFFER_MAX_BYTES"); return this; }
        public Builder bodyMaxBytes(int v) { this.bodyMaxBytes = v; mark("HINDSIGHT_BODY_MAX_BYTES"); return this; }
        public Builder sqlRowsMax(int v) { this.sqlRowsMax = v; mark("HINDSIGHT_SQL_ROWS_MAX"); return this; }
        public Builder sqlRepeatFull(int v) { this.sqlRepeatFull = v; mark("HINDSIGHT_SQL_REPEAT_FULL"); return this; }
        public Builder capturesPerHour(int v) { this.capturesPerHour = v; mark("HINDSIGHT_CAPTURES_PER_HOUR"); return this; }
        public Builder storeMaxBytes(long v) { this.storeMaxBytes = v; mark("HINDSIGHT_STORE_MAX_BYTES"); return this; }
        public Builder latencyTriggerMs(long v) { this.latencyTriggerMs = v; mark("HINDSIGHT_LATENCY_TRIGGER_MS"); return this; }
        public Builder recorderErrorLimit(int v) { this.recorderErrorLimit = v; mark("HINDSIGHT_AGENT_ERROR_LIMIT"); return this; }
        public Builder pseudonymKey(String v) { this.pseudonymKey = v; mark("HINDSIGHT_PSEUDONYM_KEY"); return this; }
        public Builder storeDir(Path v) { this.storeDir = v; mark("HINDSIGHT_STORE_DIR"); return this; }
        public Builder appName(String v) { this.appName = v; mark("HINDSIGHT_APP_NAME"); return this; }

        public RecorderConfig build() { return new RecorderConfig(this); }

        private void mark(String name) { sources.put(name, "직접 지정"); }

        // ── 환경변수 읽기 ───────────────────────────────────────────────────

        private String readOptional(Function<String, String> env, String name) {
            String raw = env.apply(name);
            if (raw == null || raw.isBlank()) {
                sources.put(name, "없음");
                return null;
            }
            sources.put(name, "환경변수");
            return raw;
        }

        private String readString(Function<String, String> env, String name, String fallback) {
            String raw = env.apply(name);
            if (raw == null || raw.isBlank()) {
                sources.put(name, "기본값");
                return fallback;
            }
            sources.put(name, "환경변수");
            return raw;
        }

        private int readInt(Function<String, String> env, String name, int fallback) {
            long v = readLong(env, name, fallback);
            if (v > Integer.MAX_VALUE) {
                throw new InvalidRecorderConfigException(name, String.valueOf(v),
                        "int 로 담을 수 있는 범위를 넘는다");
            }
            return (int) v;
        }

        private long readLong(Function<String, String> env, String name, long fallback) {
            String raw = env.apply(name);
            if (raw == null || raw.isBlank()) {
                sources.put(name, "기본값");
                return fallback;
            }
            long parsed;
            try {
                parsed = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                // 🔴 여기서 fallback 으로 돌아가지 않는다. 사용자는 자기가 적은 값이
                //    먹은 줄 알고, 상한이 기본값인 채로 운영에 나간다.
                throw new InvalidRecorderConfigException(name, raw,
                        "숫자가 아니다. 단위 없이 바이트/초/개수를 숫자로만 적는다 (예: 65536)");
            }
            if (parsed <= 0) {
                throw new InvalidRecorderConfigException(name, raw, "0 보다 커야 한다");
            }
            sources.put(name, "환경변수");
            return parsed;
        }
    }

    /** 사람이 읽는 한 줄 요약. 어디서 온 값인지까지 보인다. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        sources.forEach((name, source) -> lines.add(name + " = " + valueOf(name) + " (" + source + ")"));
        return List.copyOf(lines);
    }

    private String valueOf(String name) {
        return switch (name) {
            case "HINDSIGHT_WINDOW_SECONDS" -> String.valueOf(windowSeconds);
            case "HINDSIGHT_BUFFER_MAX_BYTES" -> String.valueOf(bufferMaxBytes);
            case "HINDSIGHT_BODY_MAX_BYTES" -> String.valueOf(bodyMaxBytes);
            case "HINDSIGHT_SQL_ROWS_MAX" -> String.valueOf(sqlRowsMax);
            case "HINDSIGHT_SQL_REPEAT_FULL" -> String.valueOf(sqlRepeatFull);
            case "HINDSIGHT_CAPTURES_PER_HOUR" -> String.valueOf(capturesPerHour);
            case "HINDSIGHT_STORE_MAX_BYTES" -> String.valueOf(storeMaxBytes);
            case "HINDSIGHT_LATENCY_TRIGGER_MS" -> String.valueOf(latencyTriggerMs);
            case "HINDSIGHT_AGENT_ERROR_LIMIT" -> String.valueOf(recorderErrorLimit);
            // 🔴 키 자체는 절대 안 찍는다. 있는지 없는지만.
            case "HINDSIGHT_PSEUDONYM_KEY" -> pseudonymKey == null ? "(없음)" : "(설정됨)";
            case "HINDSIGHT_STORE_DIR" -> String.valueOf(storeDir);
            case "HINDSIGHT_APP_NAME" -> appName;
            default -> "?";
        };
    }
}
