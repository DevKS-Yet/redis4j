package redis4j.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RESP2 응답 값. sealed 계층이라 인코더 switch가 망라적이다.
 * 중첩 레코드는 항상 {@code Reply.Xxx} 로 정규화해 참조한다.
 */
public sealed interface Reply
        permits Reply.Simple, Reply.Error, Reply.Integer, Reply.Bulk, Reply.Array, Reply.Nil,
                Reply.NilArray {

    /** +단순 문자열 (CR/LF 불가) */
    record Simple(String value) implements Reply {}

    /** -에러 (첫 토큰이 에러 코드, CR/LF 불가) */
    record Error(String message) implements Reply {}

    /** :정수 */
    record Integer(long value) implements Reply {}

    /** $벌크 문자열 (바이트 안전) */
    record Bulk(byte[] value) implements Reply {}

    /** *배열 */
    record Array(List<Reply> items) implements Reply {}

    /** Null 벌크 ($-1) */
    record Nil() implements Reply {}

    /** Null 배열 (*-1) — EXEC 취소(WATCH) 응답 */
    record NilArray() implements Reply {}

    static Reply ok() { return new Simple("OK"); }
    static Reply pong() { return new Simple("PONG"); }
    static Reply error(String message) { return new Error(message); }
    static Reply bulk(String value) { return new Bulk(value.getBytes(StandardCharsets.UTF_8)); }
    static Reply bulk(byte[] value) { return new Bulk(value); }
}
