package io.hindsight.core.replay;

import io.hindsight.model.SqlShapes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 질의를 «모양»으로 접는 규칙이 어디까지 듣고 어디서부터 안 듣나.
 *
 * <p>🔴 <b>안 듣는 경우도 검사로 적어 둔다.</b> 한계를 문서에만 적으면 다음 사람이
 * 「되는 줄 알고」 쓰고, 안 되는 것을 직접 부딪혀 알아낸다. 검사로 적어 두면
 * 누가 그 한계를 고쳤을 때 <b>이 검사가 먼저 실패해서</b> 알려 준다.
 */
@DisplayName("질의 모양 접기 — 어디까지 듣나")
class SqlShapesTest {

    @Nested
    @DisplayName("✅ 듣는 것")
    class 듣는것 {

        @Test
        @DisplayName("숫자 값이 다르면 한 모양으로 접힌다 — N+1 이 여기서 잡힌다")
        void 숫자값을_접는다() {
            assertThat(SqlShapes.of("select * from member where id = 7").hash())
                    .isEqualTo(SqlShapes.of("select * from member where id = 8").hash());
        }

        @Test
        @DisplayName("문자열 값이 다르면 한 모양으로 접힌다")
        void 문자열값을_접는다() {
            assertThat(SqlShapes.of("select * from member where name = '홍길동'").hash())
                    .isEqualTo(SqlShapes.of("select * from member where name = '김철수'").hash());
        }

        @Test
        @DisplayName("공백과 줄바꿈이 달라도 한 모양이다 — 하이버네이트가 질의를 예쁘게 찍어도 상관없다")
        void 공백을_접는다() {
            assertThat(SqlShapes.of("select *\n  from member\n  where id = ?").hash())
                    .isEqualTo(SqlShapes.of("select * from member where id = ?").hash());
        }

        @Test
        @DisplayName("진짜로 다른 질의는 다른 모양이다")
        void 다른_질의는_다른_모양() {
            assertThat(SqlShapes.of("select * from member").hash())
                    .isNotEqualTo(SqlShapes.of("select * from orders").hash());
        }

        @Test
        @DisplayName("🔴 SQL 을 못 알아냈을 때는 「모른다」 모양이 된다 — 빈 문자열이 아니다")
        void 모르는_질의는_모른다_모양() {
            assertThat(SqlShapes.of(null).normalized()).isEqualTo(SqlShapes.UNKNOWN);
            assertThat(SqlShapes.of(null).hash()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("🔴 안 듣는 것 — 알고 두는 한계다")
    class 안듣는것 {

        @Test
        @DisplayName("🔴 IN 절의 «자리 개수»가 다르면 다른 모양으로 세인다")
        void IN절의_자리_개수는_못_접는다() {
            String 둘 = "select * from member where id in (1, 2)";
            String 셋 = "select * from member where id in (1, 2, 3)";

            // 값은 전부 ? 로 바뀌지만 «개수»가 남는다.
            assertThat(SqlShapes.of(둘).normalized()).isEqualTo("select * from member where id in (?, ?)");
            assertThat(SqlShapes.of(셋).normalized()).isEqualTo("select * from member where id in (?, ?, ?)");
            assertThat(SqlShapes.of(둘).hash()).isNotEqualTo(SqlShapes.of(셋).hash());
        }

        @Test
        @DisplayName("🔴 그래서 배치 크기가 들쭉날쭉한 앱에서는 모양이 여러 개로 흩어진다")
        void 배치_크기가_다르면_모양이_흩어진다() {
            long 서로다른모양 = java.util.stream.IntStream.rangeClosed(1, 10)
                    .mapToObj(n -> "select * from member where id in ("
                            + "?, ".repeat(n - 1) + "?)")
                    .map(sql -> SqlShapes.of(sql).hash())
                    .distinct()
                    .count();

            // 같은 코드가 낸 같은 질의인데 모양이 10개로 보인다.
            assertThat(서로다른모양).isEqualTo(10);
        }
    }

    /**
     * 🔴 위 한계가 채점에 미치는 영향을 «말로 적어» 둔다.
     *
     * <p>모양이 흩어지면 「한 모양이 N번 반복」이라는 신호가 <b>약해진다</b> — N+1 을
     * 배치로 고친 코드가 「모양 10개가 1번씩」으로 보여서, 반복 오라클이 통과시킨다.
     * 그건 실제로 «고쳐진» 것이므로 통과가 맞다. 반대로 <b>원래부터 배치 크기가 들쭉날쭉한
     * 앱에서는 N+1 을 놓칠 수 있다.</b>
     *
     * <p>⬜ 진짜 앱의 SQL 로 얼마나 흔한지는 <b>안 쟀다.</b> 고치는 방법은
     * {@code in (?, ?, ?)} 를 {@code in (?)} 로 한 번 더 접는 것인데,
     * 🔴 그러면 「값 3개로 부른 것」과 「값 300개로 부른 것」이 같아진다 —
     * 그 둘은 성능상 «다른 사실»이라 접어도 되는지가 분명하지 않다. 재고 나서 정한다.
     */
    @Test
    @DisplayName("이 한계는 문서가 아니라 여기에 적혀 있다")
    void 한계를_여기_적어_둔다() {
        assertThat(SqlShapes.UNKNOWN).isEqualTo("(알 수 없음)");
    }
}
