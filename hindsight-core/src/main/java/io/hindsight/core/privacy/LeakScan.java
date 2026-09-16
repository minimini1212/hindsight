package io.hindsight.core.privacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🔴 <b>밖으로 나가기 «직전»에 한 번 더 훑는다.</b>
 *
 * <h2>왜 가명화가 있는데 또 보나</h2>
 * {@link Pseudonymizer} 는 <b>기록의 정해진 자리</b>(헤더 · 본문 · 질의 파라미터)를 훑는다.
 * 그런데 PR 본문은 그 기록에서 <b>글을 지어낸 것</b>이다 — 예외 메시지를 옮겨 적고,
 * 질의문을 잘라 붙이고, 스택 트레이스 한 줄을 인용한다.
 * <b>가명화가 훑지 않은 자리에서 값이 흘러나올 수 있다.</b>
 *
 * <p>🔴 그리고 PR 본문은 <b>이 도구가 만드는 것 중 유일하게 저장소 밖으로 나가는 글</b>이다.
 * GitHub 에 올라가는 순간 되돌릴 수 없다 — 지워도 알림 메일과 색인에는 남는다.
 * 그래서 「가명화가 이미 됐을 것이다」에 기대지 않고 <b>나가기 직전에 다시 본다.</b>
 *
 * <h2>⚠️ 이건 가명화가 아니다 — 찾으면 «막고», 그리고 «가린다»</h2>
 * <ol>
 *   <li><b>막는다</b> — 자동으로 여는 것을 멈추고 사람을 부른다</li>
 *   <li><b>가린다</b> — 본문에서도 값을 가린다 ({@link #가린_글})</li>
 * </ol>
 *
 * <p>🔴 처음에는 막기만 했다. 그런데 <b>막아도 본문에는 원본이 그대로 남는다</b> —
 * 사람이 읽어 보고 「괜찮겠지」 하며 손으로 열면 막은 것이 아무 뜻이 없어진다.
 * PR 본문은 <b>복사해서 붙이라고 만든 글</b>이라 더 그렇다.
 *
 * <p>⚠️ 그래도 <b>조용히 지우지는 않는다.</b> 무엇을 어디서 가렸는지가 결과에 같이 나가고,
 * 원본은 기록에 그대로 있다 — {@code hs show <번호>}.
 *
 * <h2>🔴 못 찾는 것이 있다는 사실이 이 검사의 «결과»에 같이 나간다</h2>
 * 이름 · 주소 · 주문 내용처럼 <b>모양이 없는 개인정보는 정규식으로 못 찾는다.</b>
 * 그걸 안 적으면 「검사했으니 깨끗하다」로 읽히는데, 그게 이 프로젝트가 잡으려는
 * 「모름을 없음으로 접는」 결함이다.
 */
public final class LeakScan {

    private LeakScan() {}

    /**
     * 모양으로 찾을 수 있는 것들.
     *
     * <p>🔴 {@link Pseudonymizer} 와 <b>같은 모양</b>을 본다. 두 벌로 적으면 한쪽만 고쳐지고,
     * 그 순간 「가명화는 지웠는데 검사는 못 찾는」 또는 그 반대의 자리가 생긴다.
     */
    private static final Map<String, Pattern> 모양들 = new LinkedHashMap<>(Map.of(
            "이메일", Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"),
            "휴대폰 번호", Pattern.compile("\\b01[0-9][- ]?\\d{3,4}[- ]?\\d{4}\\b"),
            "주민등록번호", Pattern.compile("\\b\\d{6}[- ]?[1-4]\\d{6}\\b"),
            "카드번호", Pattern.compile("\\b(?:\\d[ -]?){13,16}\\b"),
            // 아래 둘은 가명화에는 없다. 기록의 «자리»가 아니라 글 속에서만 나타나는 모양이라서.
            "베어러 토큰", Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
            "JWT", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}")
    ));

    /** 🔴 모양으로는 못 찾는 것. 결과에 «항상» 같이 나간다. */
    public static final List<String> 모양으로는_못_찾는_것 = List.of(
            "사람 이름 · 주소 · 회사 이름 — 모양이 없다",
            "주문 내용 · 상품명 — 그 자체로는 평범한 글자다",
            "내부 호스트 이름 · 사설 IP — 새는 건 맞지만 개인정보는 아니라 여기서 안 본다"
    );

    /**
     * 찾은 것 하나.
     *
     * @param 무엇  어떤 모양인가 (이메일 · JWT …)
     * @param 어디  글에서 몇 번째 글자인가
     * @param 조각  🔴 <b>값 자체가 아니라 가려진 조각.</b> 찾았다고 로그에 원본을 또 적으면
     *              새는 자리를 하나 더 만드는 것이다
     * @param 원본길이 글에서 이 값이 차지한 길이. 글에서 지울 때 필요하다
     */
    public record 발견(String 무엇, int 어디, String 조각, int 원본길이) {}

    /**
     * @param 글 밖으로 나갈 글. {@code null} 이면 「안 봤다」이므로 {@code null} 을 돌려준다
     * @return 🔴 {@code null} 은 <b>「안 봤다」</b>, 빈 목록은 <b>「보았고 모양으로는 없었다」</b>.
     *         둘을 같게 다루면 검사를 안 돌린 PR 이 「깨끗함」으로 나간다
     */
    public static List<발견> 훑는다(String 글) {
        if (글 == null) {
            return null;
        }
        List<발견> 찾은것 = new ArrayList<>();
        모양들.forEach((이름, 모양) -> {
            Matcher m = 모양.matcher(글);
            while (m.find()) {
                찾은것.add(new 발견(이름, m.start(), 가린다(m.group()), m.group().length()));
            }
        });
        찾은것.sort(java.util.Comparator.comparingInt(발견::어디));
        return List.copyOf(찾은것);
    }

    /**
     * 찾은 자리를 <b>글에서도</b> 가린다.
     *
     * <h2>🔴 막기만 하고 안 가리면, 손으로 열 때 그대로 나간다</h2>
     * 모양이 걸리면 자동으로 열지는 않는다. 그런데 <b>본문에는 원본이 그대로 남는다.</b>
     * 사람이 읽어 보고 「괜찮겠지」 하며 손으로 열면, 막은 것이 아무 뜻이 없어진다.
     * PR 본문은 복사해서 붙이라고 만든 글이라 더 그렇다.
     *
     * <h2>⚠️ 가리는 것은 손실이다. 그래서 «어디를 가렸는지»가 같이 나간다</h2>
     * 원본이 필요하면 기록에 그대로 있다 — {@code hs show <번호>}.
     * 🔴 PR 은 기록에서 «파생된» 글이고, 밖으로 나가는 쪽은 이쪽뿐이다.
     *
     * @return 가린 글. 🔴 {@code null} 이 들어오면 {@code null} 이다 — 빈 문자열로 바꾸지 않는다
     */
    public static String 가린_글(String 글, List<발견> 찾은것) {
        if (글 == null || 찾은것 == null || 찾은것.isEmpty()) {
            return 글;
        }
        // 🔴 뒤에서부터 바꾼다. 앞에서부터 바꾸면 길이가 달라져 뒤쪽 «어디»가 전부 밀린다.
        StringBuilder sb = new StringBuilder(글);
        찾은것.stream()
                .sorted(java.util.Comparator.comparingInt(발견::어디).reversed())
                .forEach(f -> {
                    int 끝 = f.어디() + f.원본길이();
                    if (f.어디() >= 0 && 끝 <= sb.length()) {
                        sb.replace(f.어디(), 끝, f.조각());
                    }
                });
        return sb.toString();
    }

    /**
     * 🔴 앞뒤 몇 글자만 남긴다. 「무엇이 걸렸나」를 사람이 알아볼 정도면 충분하고,
     * 그 이상은 <b>검사 결과가 또 하나의 유출</b>이 된다.
     */
    static String 가린다(String 값) {
        if (값.length() <= 6) {
            return "*".repeat(값.length());
        }
        return 값.substring(0, 3) + "*".repeat(Math.min(값.length() - 6, 8)) + 값.substring(값.length() - 3);
    }
}
