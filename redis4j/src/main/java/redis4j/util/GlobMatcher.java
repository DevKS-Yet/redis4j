package redis4j.util;

/**
 * Redis glob 매칭: {@code *}(임의 시퀀스) · {@code ?}(임의 1자) · {@code [...]}(문자 클래스,
 * {@code ^} 부정 · {@code a-z} 범위) · {@code \\} 이스케이프. 대소문자 구분.
 * KEYS/SCAN 과 PSUBSCRIBE 패턴 매칭에서 공용으로 쓴다.
 */
public final class GlobMatcher {

    private GlobMatcher() {}

    public static boolean matches(String pat, String s) {
        int p = 0;
        int si = 0;
        int starP = -1;
        int starS = -1;
        int pn = pat.length();
        int sn = s.length();
        while (si < sn) {
            char pc = p < pn ? pat.charAt(p) : '\0';
            if (p < pn && pc == '?') {
                p++;
                si++;
            } else if (p < pn && pc == '\\' && p + 1 < pn) {
                if (pat.charAt(p + 1) == s.charAt(si)) {
                    p += 2;
                    si++;
                } else if (starP >= 0) {
                    p = starP + 1;
                    si = ++starS;
                } else {
                    return false;
                }
            } else if (p < pn && pc == '[') {
                int[] r = matchClass(pat, p, s.charAt(si));
                if (r[0] == -1) {                                // 닫는 ] 없음 → '[' 리터럴
                    if (s.charAt(si) == '[') {
                        p++;
                        si++;
                    } else if (starP >= 0) {
                        p = starP + 1;
                        si = ++starS;
                    } else {
                        return false;
                    }
                } else if (r[0] == 1) {
                    p = r[1];
                    si++;
                } else if (starP >= 0) {
                    p = starP + 1;
                    si = ++starS;
                } else {
                    return false;
                }
            } else if (p < pn && pc == '*') {
                starP = p;
                starS = si;
                p++;
            } else if (p < pn && pc == s.charAt(si)) {
                p++;
                si++;
            } else if (starP >= 0) {
                p = starP + 1;
                si = ++starS;
            } else {
                return false;
            }
        }
        while (p < pn && pat.charAt(p) == '*') {
            p++;
        }
        return p == pn;
    }

    /**
     * '[' 위치에서 문자 클래스를 파싱해 c 매칭 여부와 ']' 다음 인덱스를 반환.
     * 반환 {@code [matched(0/1), nextIndex]}; 닫는 ']' 없으면 {@code [-1,-1]}(리터럴 처리).
     */
    private static int[] matchClass(String pat, int start, char c) {
        int i = start + 1;
        boolean negate = false;
        if (i < pat.length() && pat.charAt(i) == '^') {
            negate = true;
            i++;
        }
        boolean matched = false;
        while (i < pat.length() && pat.charAt(i) != ']') {
            char cur = pat.charAt(i);
            if (cur == '\\' && i + 1 < pat.length()) {
                if (pat.charAt(i + 1) == c) {
                    matched = true;
                }
                i += 2;
            } else if (i + 2 < pat.length() && pat.charAt(i + 1) == '-' && pat.charAt(i + 2) != ']') {
                char lo = cur;
                char hi = pat.charAt(i + 2);
                if (lo > hi) {
                    char t = lo;
                    lo = hi;
                    hi = t;
                }
                if (c >= lo && c <= hi) {
                    matched = true;
                }
                i += 3;
            } else {
                if (cur == c) {
                    matched = true;
                }
                i++;
            }
        }
        if (i >= pat.length()) {
            return new int[]{-1, -1};                            // 닫는 ] 없음
        }
        if (negate) {
            matched = !matched;
        }
        return new int[]{matched ? 1 : 0, i + 1};
    }
}
