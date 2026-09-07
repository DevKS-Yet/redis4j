package redis4j.server;

/** 연결별 가변 상태. 현재는 QUIT 플래그만; 이후 단계(트랜잭션·구독 등)에서 확장한다. */
public final class ConnectionState {

    private boolean quit;

    public boolean isQuit() {
        return quit;
    }

    public void setQuit(boolean quit) {
        this.quit = quit;
    }
}
