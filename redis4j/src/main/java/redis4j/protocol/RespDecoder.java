package redis4j.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 클라이언트 요청을 RESP2로 디코딩한다. 인자는 <b>바이트 안전</b>하게 {@code byte[]} 로 반환한다
 * (값이 임의 바이트일 수 있으므로). 명령명·키는 상위 계층이 필요 시 UTF-8로 디코딩한다.
 * {@code *} 로 시작하지 않으면 inline 명령(공백 분리)으로 처리한다.
 */
public final class RespDecoder {

    private RespDecoder() {}

    /** 명령 하나를 인자(byte[]) 리스트로 읽는다. 연결 종료(EOF)면 {@code null}. 위반이면 {@link ProtocolException}. */
    public static List<byte[]> readCommand(InputStream in) throws IOException {
        int first = in.read();
        if (first == -1) {
            return null;                                   // EOF
        }
        if (first == '*') {
            int count = parseInt(readLine(in));
            if (count <= 0) {
                return List.of();
            }
            List<byte[]> args = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int type = in.read();
                if (type != '$') {
                    throw new ProtocolException(
                            "expected '$', got '" + (type == -1 ? "EOF" : (char) type) + "'");
                }
                int len = parseInt(readLine(in));
                if (len < 0) {
                    args.add(null);
                    continue;
                }
                byte[] buf = in.readNBytes(len);
                if (buf.length != len) {
                    throw new ProtocolException("unexpected EOF in bulk string");
                }
                in.read();                                 // trailing CR
                in.read();                                 // trailing LF
                args.add(buf);
            }
            return args;
        }
        // inline 명령 (공백 분리, 텍스트로 취급)
        String rest = readLine(in);
        String line = ((char) first) + (rest == null ? "" : rest);
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<byte[]> args = new ArrayList<>();
        for (String token : trimmed.split("\\s+")) {
            args.add(token.getBytes(StandardCharsets.UTF_8));
        }
        return args;
    }

    private static int parseInt(String s) {
        try {
            return java.lang.Integer.parseInt(s == null ? "" : s.strip());
        } catch (NumberFormatException e) {
            throw new ProtocolException("invalid length header: '" + s + "'");
        }
    }

    /** CRLF(또는 관용적으로 LF) 종료 줄. 종결자는 제외. EOF면 null. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return sb.toString();
            }
            if (c == '\r') {
                int next = in.read();
                if (next == '\n' || next == -1) {
                    return sb.toString();
                }
                sb.append('\r').append((char) next);
            } else {
                sb.append((char) c);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
