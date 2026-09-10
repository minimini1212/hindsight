package io.hindsight.core.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.hindsight.model.Event;
import io.hindsight.model.Recording;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 기록 파일을 읽고 쓴다.
 *
 * <h2>「모름」을 살려서 저장한다</h2>
 * 🔴 {@code null} 필드를 <b>생략하지 않는다.</b> 이 프로젝트에서 {@code null} 은
 * 「안 봤다/못 봤다」라는 <b>내용이 있는 값</b>이다. 흔히 하듯 빈 필드를 지우면
 * 「못 알아냈다」와 「그런 게 없었다」가 파일에서 똑같아 보인다 — 이 도구가 남의 코드에서
 * 잡으려는 바로 그 결함이다. 그래서 {@code NON_NULL} 을 <b>일부러 안 쓴다.</b>
 *
 * <h2>모르는 판 번호는 거부한다</h2>
 * 필드가 늘거나 뜻이 바뀐 파일을 옛 코드가 「대충」 읽으면, 그 잘못된 재생 결과가
 * 그대로 LLM 진단의 근거가 된다. 조용히 틀리느니 시끄럽게 멈춘다.
 */
public final class RecordingCodec {

    private final ObjectMapper mapper;

    public RecordingCodec() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .addMixIn(Event.class, EventMixin.class)
                // 🔴 여기서 getter 자동 인식을 끄지 말 것 — 2026-09-10 시도했다가 되돌렸다.
                //
                // 문제: model 에는 Jackson 애너테이션을 못 붙인다(그 라이브러리가 남의 JVM 으로
                // 딸려 들어가므로). 그래서 Integrity.isIncomplete() 같은 파생 메서드를
                // @JsonIgnore 로 막을 수가 없고, 그대로 두면 「incomplete」 필드로 새어
                // 읽을 때 왕복이 깨진다.
                //
                // 시도한 해법: setVisibility(GETTER/IS_GETTER, NONE).
                // 결과: Jackson 은 record 접근자도 getter 로 본다. 전부 꺼져서 「{ }」가 나왔다.
                //
                // 지금 방식: model 의 파생 메서드를 getXxx·isXxx 로 짓지 않는다(hasGaps,
                // windowFellShort, allowsAutoPullRequest). 규칙을 사람이 기억하는 대신
                // RecordingCodecTest 의 왕복 테스트가 지킨다 — 실제로 그 테스트가 이걸 잡았다.
                // 시각을 숫자가 아니라 사람이 읽는 ISO-8601 로. 기록은 사람도 열어 본다.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // 🔴 모르는 필드를 만나면 멈춘다. 조용히 버리면 쓰는 쪽과 읽는 쪽이
                //    갈라진 것을 아무도 모른 채 몇 주가 지난다.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public String toJson(Recording recording) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(recording);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("기록을 JSON 으로 쓰지 못했다: " + recording.id(), e);
        }
    }

    public void write(Recording recording, Path target) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, toJson(recording), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("기록 파일을 쓰지 못했다: " + target, e);
        }
    }

    public Recording fromJson(String json) {
        JsonNode root = parseTree(json);
        requireReadableVersion(root);
        try {
            return mapper.treeToValue(root, Recording.class);
        } catch (JsonProcessingException e) {
            throw new UnreadableRecordingException(
                    "판 번호는 읽을 수 있는데 내용을 못 읽었다. 쓰는 쪽과 읽는 쪽이 갈라졌을 수 있다: "
                            + e.getOriginalMessage(), e);
        }
    }

    public Recording read(Path source) {
        try {
            return fromJson(Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("기록 파일을 읽지 못했다: " + source, e);
        }
    }

    private JsonNode parseTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UnreadableRecordingException("JSON 으로 읽히지 않는다: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * 🔴 내용을 보기 <b>전에</b> 판 번호부터 본다.
     *
     * <p>순서가 중요하다. 먼저 통째로 읽어 보고 나서 번호를 확인하면, 실패 메시지가
     * 「필드 이름이 이상하다」처럼 나와서 진짜 원인(판이 다르다)이 가려진다.
     */
    private void requireReadableVersion(JsonNode root) {
        JsonNode versionNode = root.get("schemaVersion");
        if (versionNode == null || !versionNode.isInt()) {
            throw new UnreadableRecordingException(
                    "schemaVersion 이 없다. 기록 파일이 아니거나 판 번호 이전에 만들어진 파일이다.");
        }
        int version = versionNode.asInt();
        if (version > Recording.CURRENT_SCHEMA_VERSION) {
            throw new UnreadableRecordingException(
                    "이 기록은 " + version + " 판인데 이 코드는 "
                            + Recording.CURRENT_SCHEMA_VERSION + " 판까지만 안다. "
                            + "기본값으로 대충 읽지 않는다 — 잘못 읽은 재생 결과가 진단의 근거가 되기 때문이다. "
                            + "Hindsight 를 올려라.");
        }
        if (version < 1) {
            throw new UnreadableRecordingException("schemaVersion 이 " + version + " 이다. 1 이상이어야 한다.");
        }
    }

    /** 기록을 읽을 수 없을 때. 🔴 조용히 넘어가는 대신 이걸 던진다. */
    public static class UnreadableRecordingException extends RuntimeException {
        public UnreadableRecordingException(String message) {
            super(message);
        }

        public UnreadableRecordingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
