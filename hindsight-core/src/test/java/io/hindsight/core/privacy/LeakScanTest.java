package io.hindsight.core.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR 본문이 밖으로 나가기 직전의 마지막 검사.
 *
 * <p>🔴 <b>이 검사가 「깨끗하다」고 말하는 범위는 좁다.</b> 그 좁음이 결과에 같이 나가는지가
 * 여기서 가장 중요한 단언이다 — 안 나가면 「훑었으니 안전하다」로 읽힌다.
 */
@DisplayName("나가기 직전에 한 번 더 훑는다")
class LeakScanTest {

    @Nested
    @DisplayName("모양으로 찾는 것")
    class 찾는다 {

        @Test
        @DisplayName("이메일 · 휴대폰 · 주민번호 · 카드번호")
        void 흔한_네_가지() {
            String 글 = """
                    예외 메시지: 없는 회원 hong@example.com
                    연락처 010-1234-5678 로 안내했고
                    주민번호 900101-1234567 과
                    카드 4111-1111-1111-1111 이 본문에 있었다
                    """;

            List<LeakScan.발견> 찾은것 = LeakScan.훑는다(글);

            assertThat(찾은것).extracting(LeakScan.발견::무엇)
                    .contains("이메일", "휴대폰 번호", "주민등록번호", "카드번호");
        }

        @Test
        @DisplayName("🔴 토큰 — 가명화가 «헤더»에서 지우는 것이라 본문 글에서는 안 지워진다")
        void 토큰() {
            String 글 = "스택에 Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345 가 찍혀 있었다";

            assertThat(LeakScan.훑는다(글)).extracting(LeakScan.발견::무엇).contains("베어러 토큰");
        }

        @Test
        @DisplayName("JWT 는 헤더 이름 없이 본문에 박혀 있어도 찾는다")
        void jwt() {
            String 글 = "응답 본문: {\"t\":\"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcd\"}";

            assertThat(LeakScan.훑는다(글)).extracting(LeakScan.발견::무엇).contains("JWT");
        }

        @Test
        @DisplayName("🔴 찾은 값을 그대로 다시 적지 않는다 — 검사 결과가 또 하나의 유출이 된다")
        void 찾은_값은_가린다() {
            List<LeakScan.발견> 찾은것 = LeakScan.훑는다("hong.gildong@example.com 이 있었다");

            assertThat(찾은것).isNotEmpty();
            assertThat(찾은것.getFirst().조각())
                    .doesNotContain("gildong")
                    .contains("*");
        }

        @Test
        @DisplayName("어디서 걸렸는지 자리를 알려 준다")
        void 자리를_알려준다() {
            String 앞 = "앞부분 글자들 ";
            List<LeakScan.발견> 찾은것 = LeakScan.훑는다(앞 + "hong@example.com");

            assertThat(찾은것.getFirst().어디()).isEqualTo(앞.length());
        }
    }

    @Nested
    @DisplayName("🔴 「안 봤다」와 「보았는데 없다」를 구별한다")
    class 모름과_없음 {

        @Test
        @DisplayName("본문이 null 이면 빈 목록이 아니라 null 이다")
        void 안_봤으면_null() {
            assertThat(LeakScan.훑는다(null))
                    .as("빈 목록으로 돌려주면 검사를 «안 돌린» PR 이 「깨끗함」으로 나간다")
                    .isNull();
        }

        @Test
        @DisplayName("깨끗한 글은 빈 목록이다")
        void 깨끗하면_빈_목록() {
            assertThat(LeakScan.훑는다("주문 목록 요청이 느렸다. 같은 질의가 5번 반복됐다."))
                    .isNotNull()
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("🔴 못 찾는 것의 목록이 «비어 있지 않다» — 비면 「전부 찾는다」로 읽힌다")
    void 못_찾는_것을_말한다() {
        assertThat(LeakScan.모양으로는_못_찾는_것).isNotEmpty();
        assertThat(String.join(" ", LeakScan.모양으로는_못_찾는_것))
                .as("모양 없는 개인정보를 못 찾는다는 사실이 분명해야 한다")
                .contains("이름");
    }

    @Test
    @DisplayName("사람 이름과 주소는 «못 찾는다» — 그걸 시험으로 못 박아 둔다")
    void 이름은_못_찾는다() {
        // 🔴 이건 「고쳐야 할 실패」가 아니라 「이 검사의 한계」다.
        //    한계를 시험으로 적어 두면, 나중에 「훑었으니 안전하다」고 말하는 코드가
        //    생길 때 이 시험이 근거가 된다.
        assertThat(LeakScan.훑는다("홍길동 님이 서울시 강남구로 주문했다")).isEmpty();
    }
}
