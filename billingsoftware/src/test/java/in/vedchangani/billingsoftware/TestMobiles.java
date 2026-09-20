package in.vedchangani.billingsoftware;

import java.util.concurrent.atomic.AtomicLong;

/** Unique, valid (6xxxxxxxxx) mobile numbers for test accounts that must be able to check out. */
public final class TestMobiles {

    private static final AtomicLong NEXT = new AtomicLong(6_000_000_000L);

    private TestMobiles() {
    }

    public static String next() {
        return String.valueOf(NEXT.getAndIncrement());
    }
}
