package redis4j.protocol;

/** RESP2 프로토콜 위반 시 던진다. 해당 연결만 종료하고 서버는 유지한다. */
public final class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }
}
