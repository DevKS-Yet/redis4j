package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.server.ConnectionState;

import java.util.List;
import java.util.Locale;

/**
 * 명령명을 처리기로 라우팅한다. 상태를 갖지 않으며(연결 상태는 인자로 전달) 스레드 안전하다.
 * 이번 단계(REQ-NET-FUNC-01)는 PING/ECHO/COMMAND/QUIT 만 처리하고, 자료 저장은 다루지 않는다.
 */
public final class CommandDispatcher {

    public Reply dispatch(List<String> args, ConnectionState state) {
        if (args == null || args.isEmpty()) {
            return Reply.error("ERR empty command");
        }
        String name = args.get(0).toUpperCase(Locale.ROOT);
        return switch (name) {
            case "PING" -> args.size() > 1 ? Reply.bulk(args.get(1)) : Reply.pong();
            case "ECHO" -> args.size() == 2
                    ? Reply.bulk(args.get(1))
                    : Reply.error("ERR wrong number of arguments for 'echo' command");
            case "COMMAND" -> new Reply.Array(List.of());   // 최소 응답(빈 배열)
            case "QUIT" -> {
                state.setQuit(true);
                yield Reply.ok();
            }
            default -> Reply.error("ERR unknown command '" + args.get(0) + "'");
        };
    }
}
