package redis4j.server;

import redis4j.protocol.Reply;
import redis4j.protocol.RespEncoder;
import redis4j.pubsub.MessageSink;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 단일 연결의 출력 스트림에 대한 스레드 안전 쓰기 지점. 명령 응답(핸들러 스레드)과
 * 발행 메시지 푸시(발행자 스레드)가 같은 모니터로 직렬화돼 프레임이 섞이지 않는다.
 */
public final class ClientOutput implements MessageSink {

    private final OutputStream out;

    public ClientOutput(OutputStream out) {
        this.out = out;
    }

    /** 명령 응답 쓰기. 실패는 호출자(연결 루프)가 처리하도록 전파. */
    public synchronized void write(Reply reply) throws IOException {
        RespEncoder.write(out, reply);
        out.flush();
    }

    /** 발행 메시지 푸시. fire-and-forget — 연결 끊김은 무시. */
    @Override
    public void send(Reply reply) {
        try {
            write(reply);
        } catch (IOException ignored) {
            // 구독 연결이 끊겼을 뿐 — 발행자는 계속 진행한다.
        }
    }
}
