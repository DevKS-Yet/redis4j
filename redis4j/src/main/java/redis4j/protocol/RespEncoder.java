package redis4j.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** {@link Reply} 를 RESP2 와이어 포맷으로 직렬화한다. */
public final class RespEncoder {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] NIL_BYTES = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NIL_ARRAY_BYTES = "*-1\r\n".getBytes(StandardCharsets.US_ASCII);

    private RespEncoder() {}

    public static void write(OutputStream out, Reply reply) throws IOException {
        switch (reply) {
            case Reply.Simple s -> { out.write('+'); writeUtf8(out, s.value()); out.write(CRLF); }
            case Reply.Error e  -> { out.write('-'); writeUtf8(out, e.message()); out.write(CRLF); }
            case Reply.Integer i -> { out.write(':'); writeUtf8(out, Long.toString(i.value())); out.write(CRLF); }
            case Reply.Bulk b -> {
                out.write('$');
                writeUtf8(out, java.lang.Integer.toString(b.value().length));
                out.write(CRLF);
                out.write(b.value());
                out.write(CRLF);
            }
            case Reply.Nil n -> out.write(NIL_BYTES);
            case Reply.NilArray n -> out.write(NIL_ARRAY_BYTES);
            case Reply.Array a -> {
                out.write('*');
                writeUtf8(out, java.lang.Integer.toString(a.items().size()));
                out.write(CRLF);
                for (Reply item : a.items()) {
                    write(out, item);
                }
            }
        }
    }

    private static void writeUtf8(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
