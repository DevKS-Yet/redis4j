package redis4j.store;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Sorted Set 값. 멤버→score 는 {@link HashMap}(O(1) 조회), 정렬은 {@link TreeSet} 로 유지한다
 * (스킵리스트 대신 — 범위 질의 정확, 인덱스 O(n)은 학습용 클론 수용). 정렬 키는 (score, member lex).
 * 멤버는 UTF-8 문자열.
 */
public final class ZSetObject implements RedisObject {

    /** 정렬 원소: score 오름차순, 동점이면 member 사전순. */
    public record ScoredMember(double score, String member) implements Comparable<ScoredMember> {
        @Override
        public int compareTo(ScoredMember o) {
            int c = Double.compare(score, o.score);
            return c != 0 ? c : member.compareTo(o.member);
        }
    }

    private final Map<String, Double> scores = new HashMap<>();
    private final TreeSet<ScoredMember> sorted = new TreeSet<>();

    public Map<String, Double> scores() {
        return scores;
    }

    public NavigableSet<ScoredMember> sorted() {
        return sorted;
    }

    /** 추가/갱신. 0=변화 없음, 1=신규 추가, 2=score 변경. */
    public int put(String member, double score) {
        Double old = scores.get(member);
        if (old == null) {
            scores.put(member, score);
            sorted.add(new ScoredMember(score, member));
            return 1;
        }
        if (old == score) {
            return 0;
        }
        sorted.remove(new ScoredMember(old, member));
        scores.put(member, score);
        sorted.add(new ScoredMember(score, member));
        return 2;
    }

    /** 제거. 있던 멤버였으면 true. */
    public boolean remove(String member) {
        Double old = scores.remove(member);
        if (old == null) {
            return false;
        }
        sorted.remove(new ScoredMember(old, member));
        return true;
    }

    public Double scoreOf(String member) {
        return scores.get(member);
    }

    public int size() {
        return scores.size();
    }

    public boolean isEmpty() {
        return scores.isEmpty();
    }

    @Override
    public RedisType type() {
        return RedisType.ZSET;
    }
}
