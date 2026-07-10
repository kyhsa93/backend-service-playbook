package harness;

/** 한 규칙의 섹션 출력 안에 들어가는 항목 하나. Skip인 경우 name이 skip 메시지를 담는다. */
public final class Finding {
    public final Kind kind;
    public final String name;
    public final String reason;

    public Finding(Kind kind, String name, String reason) {
        this.kind = kind;
        this.name = name;
        this.reason = reason;
    }

    public static Finding pass(String name) {
        return new Finding(Kind.PASS, name, "");
    }

    public static Finding fail(String name, String reason) {
        return new Finding(Kind.FAIL, name, reason);
    }

    public static Finding skip(String message) {
        return new Finding(Kind.SKIP, message, "");
    }
}
