package harness;

/** A single entry within a rule's section output. For a skip, name holds the skip message. */
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
