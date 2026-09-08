package redis4j.server;

import redis4j.command.CommandDispatcher;
import redis4j.protocol.ProtocolException;
import redis4j.protocol.Reply;
import redis4j.protocol.RespDecoder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

/** 단일 클라이언트 연결의 요청/응답 루프. 연결마다 가상 스레드에서 실행된다. */
final class ConnectionHandler implements Runnable {

    private final Socket socket;
    private final CommandDispatcher dispatcher;

    ConnectionHandler(Socket socket, CommandDispatcher dispatcher) {
        this.socket = socket;
        this.dispatcher = dispatcher;
    }

    @Override
    public void run() {
        ConnectionState state = new ConnectionState();
        try (Socket s = socket;
             InputStream in = new BufferedInputStream(s.getInputStream());
             OutputStream rawOut = new BufferedOutputStream(s.getOutputStream())) {

            ClientOutput out = new ClientOutput(rawOut);
            state.setOutput(out);
            while (true) {
                List<byte[]> args;
                try {
                    args = RespDecoder.readCommand(in);
                } catch (ProtocolException pe) {
                    out.write(Reply.error("ERR Protocol error: " + pe.getMessage()));
                    break;                                  // 프로토콜 오류 → 해당 연결만 종료
                }
                if (args == null) {
                    break;                                  // EOF (클라이언트 종료)
                }
                if (args.isEmpty()) {
                    continue;                               // 빈 줄 무시
                }
                Reply reply = dispatcher.dispatch(args, state);
                if (reply != null) {                        // null = 구독 확인·메시지 자체 전송
                    out.write(reply);
                }
                if (state.isQuit()) {
                    break;
                }
            }
        } catch (IOException e) {
            // 클라이언트가 끊었거나 쓰기 실패 — 조용히 정리한다.
        } finally {
            dispatcher.onDisconnect(state);                 // 연결 종료 시 구독 자동 해제
        }
    }
}
