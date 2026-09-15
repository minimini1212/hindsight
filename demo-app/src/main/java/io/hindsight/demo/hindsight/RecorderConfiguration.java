package io.hindsight.demo.hindsight;

import io.hindsight.recorder.Recorder;
import io.hindsight.recorder.RecorderConfig;
import io.hindsight.recorder.http.RecordingFilter;
import io.hindsight.recorder.jdbc.RecordingDataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import javax.sql.DataSource;

/**
 * v0 기록기를 이 앱에 붙이는 자리. <b>배선만 있고 판단은 없다.</b>
 *
 * <h2>🔴 이 파일이 demo-app 쪽에 있는 이유</h2>
 * 기록기 모듈은 스프링을 <b>모른다</b>. 알게 하면 그 순간 스프링이 「관측 대상 앱 안으로
 * 들어가는 우리 코드」의 의존성이 되고, 스프링을 안 쓰는 앱이나 버전이 다른 앱에
 * 붙을 수 없게 된다. 그래서 스프링을 아는 코드는 <b>관측 «대상» 쪽에</b> 둔다.
 *
 * <h2>v0 의 한계 — 이걸 손으로 붙여야 한다는 것 자체</h2>
 * 이 파일이 존재한다는 사실이 v0 의 한계다. 아무 앱에나 못 붙고, 소스를 고쳐야 한다.
 * v1 의 진짜 에이전트는 {@code -javaagent} 옵션 한 줄로 붙으므로 이 파일이 사라진다.
 * 🧭 왜 그 순서인지: {@code docs/rules/build-order-decision.md}
 *
 * <h2>두 자리에 끼워 넣는다</h2>
 * <pre>
 *   요청 ──▶ [RecordingFilter] ──▶ 스프링 MVC ──▶ 컨트롤러
 *                                                    │
 *                                                    ▼
 *                              [감싼 DataSource] ──▶ 진짜 DataSource ──▶ H2
 * </pre>
 */
@Configuration
public class RecorderConfiguration {

    /**
     * 기록기 하나를 앱 전체가 함께 쓴다.
     *
     * <p>🔴 값은 환경변수에서 읽고, 이상하면 <b>앱이 뜨는 시점에</b> 멈춘다.
     * 잘못 설정된 채로 뜨면 상한이 다른 기록을 몇 주 동안 만들어 낸다.
     */
    @Bean(destroyMethod = "close")
    public Recorder hindsightRecorder(org.springframework.core.env.Environment environment) {
        // 🔴 System.getenv 를 직접 안 부르고 스프링의 Environment 를 통해 읽는다.
        //    이유는 편의가 아니다 — System.getenv 는 «돌고 있는 프로세스에서 바꿀 수 없어서»,
        //    「값이 이상할 때 앱이 정말 안 뜨는가」를 시험할 방법이 없다.
        //    시험할 수 없는 분기는 언젠가 틀린 채로 남는다.
        //    Environment 는 OS 환경변수를 그대로 읽으므로 운영 동작은 같다.
        RecorderConfig config = RecorderConfig.fromEnvironment(environment::getProperty);
        return new Recorder(config);
    }

    /**
     * 들어온 요청을 잡는 자리.
     *
     * <p>🔴 순서를 가장 앞으로 둔다. 뒤에 두면 앞선 필터가 던진 예외를 못 보고,
     * 그러면 <b>가장 잡고 싶은 사고가 기록에서 빠진다.</b>
     */
    @Bean
    public FilterRegistrationBean<RecordingFilter> hindsightFilter(Recorder recorder) {
        FilterRegistrationBean<RecordingFilter> registration =
                new FilterRegistrationBean<>(new RecordingFilter(recorder));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * DB 로 나가는 질의를 잡는 자리.
     *
     * <p>{@link BeanPostProcessor} 로 «만들어진 뒤» 감싸는 이유: 스프링 부트가 만든
     * {@code DataSource}(HikariCP 커넥션 풀)를 그대로 쓰면서 겉만 한 겹 두르기 위해서다.
     * 우리가 {@code DataSource} 를 직접 만들면 부트의 설정이 통째로 무시되고,
     * <b>관측하려던 앱과 다른 앱을 관측하게 된다.</b>
     *
     * <p>🔴 풀 «바깥»에서 감싸는 것이 중요하다. 안쪽에서 감싸면 풀이 커넥션을 재사용할 때
     * 같은 질의가 두 번 세인다. 이 자리가 맞다는 것은 2026-09-11 실측으로 확인했다 —
     * 주문 20건에 SQL 21번(1+20), 두 번 돌려도 21/21 이었다.
     */
    @Bean
    public static BeanPostProcessor hindsightDataSourceWrapper(
            org.springframework.beans.factory.ObjectProvider<Recorder> recorderProvider) {

        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource) {
                    return RecordingDataSource.wrap(dataSource, recorderProvider.getObject());
                }
                return bean;
            }
        };
    }
}
