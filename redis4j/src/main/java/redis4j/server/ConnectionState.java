package redis4j.server;

import redis4j.pubsub.Subscriber;

/** 연결별 가변 상태. QUIT 플래그·선택 DB 인덱스(SELECT)·출력 sink·구독 상태. */
public final class ConnectionState {

    private boolean quit;
    private int dbIndex;
    private ClientOutput output;
    private Subscriber subscriber;

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

    public ClientOutput output() {
        return output;
    }

    public void setOutput(ClientOutput output) {
        this.output = output;
    }

    public Subscriber subscriber() {
        return subscriber;
    }

    /** 첫 구독 시 이 연결의 출력 sink 로 Subscriber 를 생성해 둔다. */
    public Subscriber subscriberOrCreate() {
        if (subscriber == null) {
            subscriber = new Subscriber(output);
        }
        return subscriber;
    }

    /** 구독 모드 여부(채널·패턴 구독이 하나라도 있으면 true). 허용 명령 제한에 쓴다. */
    public boolean isSubscribed() {
        return subscriber != null && subscriber.subscriptionCount() > 0;
    }
}
