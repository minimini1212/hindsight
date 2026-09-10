package io.hindsight.model;

/**
 * 어떤 코드에서 났나.
 *
 * @param gitCommit  🔴 못 알아냈으면 {@code null}. 빈 문자열로 채우지 않는다.
 *                   기록은 2주 전 코드에서 났는데 지금 소스는 바뀌어 있을 수 있다.
 *                   재생할 때 현재 HEAD 와 다르면 <b>알린다. 막지는 않는다.</b>
 * @param gitDirty   커밋 안 된 변경이 있었나. {@code null} 이면 못 알아냈다는 뜻이고,
 *                   {@code false} 는 「확인했고 깨끗했다」는 다른 사실이다.
 * @param hostname   🔴 가명화 대상이다. 어느 서버에서 났는지가 운영 정보라서.
 */
public record AppInfo(
        String name,
        String gitCommit,
        Boolean gitDirty,
        String javaVersion,
        String agentVersion,
        String hostname
) {}
