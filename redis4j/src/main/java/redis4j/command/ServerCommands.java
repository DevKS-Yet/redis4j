package redis4j.command;

import redis4j.persistence.rdb.RdbManager;
import redis4j.protocol.Reply;

import java.io.IOException;
import java.util.List;

/**
 * 서버·영속화 관리 명령. SAVE 는 동기(일관 스냅샷 저장), BGSAVE 는 백그라운드, LASTSAVE 는
 * 마지막 저장 시각. 키 공간 락은 RdbManager 가 자체적으로 잡으므로 여기서는 감싸지 않는다.
 */
public final class ServerCommands {

    private final RdbManager rdb;

    public ServerCommands(RdbManager rdb) {
        this.rdb = rdb;
    }

    public Reply execute(String name, List<byte[]> a) {
        return switch (name) {
            case "SAVE" -> save(a);
            case "BGSAVE" -> bgsave(a);
            case "LASTSAVE" -> lastsave(a);
            default -> null;
        };
    }

    private Reply save(List<byte[]> a) {
        if (a.size() != 1) {
            return arity("save");
        }
        try {
            rdb.save();
            return Reply.ok();
        } catch (IOException e) {
            return Reply.error("ERR " + e.getMessage());
        }
    }

    private Reply bgsave(List<byte[]> a) {
        if (a.size() > 2) {                                     // BGSAVE [SCHEDULE] 허용(무시)
            return arity("bgsave");
        }
        rdb.bgsave();
        return new Reply.Simple("Background saving started");
    }

    private Reply lastsave(List<byte[]> a) {
        if (a.size() != 1) {
            return arity("lastsave");
        }
        return new Reply.Integer(rdb.lastSave());
    }

    private static Reply arity(String cmd) {
        return Reply.error("ERR wrong number of arguments for '" + cmd + "' command");
    }
}
