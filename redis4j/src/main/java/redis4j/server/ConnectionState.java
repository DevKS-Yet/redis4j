package redis4j.server;

/** 연결별 가변 상태. QUIT 플래그와 현재 선택된 논리 DB 인덱스(SELECT). 이후 단계에서 확장한다. */
public final class ConnectionState {

    private boolean quit;
    private int dbIndex;

    public boolean isQuit() {
        return quit;
    }

    public void setQuit(boolean quit) {
        this.quit = quit;
    }

    /** 이 연결이 SELECT 한 논리 DB 인덱스(기본 0). */
    public int dbIndex() {
        return dbIndex;
    }

    public void setDbIndex(int dbIndex) {
        this.dbIndex = dbIndex;
    }
}
