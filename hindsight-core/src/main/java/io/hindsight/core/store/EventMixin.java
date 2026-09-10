package io.hindsight.core.store;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.hindsight.model.Event;

/**
 * {@link Event} 를 JSON 으로 오갈 때 종류를 어떻게 적을지 Jackson 에게 알려준다.
 *
 * <h2>왜 model 이 아니라 여기 있나</h2>
 * 이 애너테이션들을 {@code Event} 에 직접 붙이면 {@code jackson-annotations} 가
 * {@code hindsight-model} 의 의존성이 된다. 그런데 에이전트가 그 모듈을 쓰므로
 * <b>Jackson 이 관측 대상 앱의 클래스패스로 딸려 들어간다.</b> 앱이 다른 버전을 쓰고 있으면
 * 우리 도구가 아니라 그 앱이 죽는다.
 *
 * <p>그래서 model 은 애너테이션 없는 순수 record 로 두고, 매핑 지식은 바깥인 여기 둔다.
 * {@code ObjectMapper.addMixIn} 이 둘을 이어 붙인다.
 *
 * <p>거부한 대안: 이름 대신 클래스 이름을 JSON 에 적는 방식({@code @class}).
 * 기록 파일이 우리 패키지 구조에 묶여, 나중에 클래스를 옮기면 옛 기록을 못 읽는다.
 * 기록은 오래 남아야 하므로 <b>이름을 직접 정해 고정한다.</b>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Event.HttpIn.class, name = "HTTP_IN"),
        @JsonSubTypes.Type(value = Event.HttpOut.class, name = "HTTP_OUT"),
        @JsonSubTypes.Type(value = Event.Sql.class, name = "SQL"),
        @JsonSubTypes.Type(value = Event.Clock.class, name = "CLOCK"),
        @JsonSubTypes.Type(value = Event.Rand.class, name = "RANDOM")
})
public interface EventMixin {}
