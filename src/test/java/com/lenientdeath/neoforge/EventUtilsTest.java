package com.lenientdeath.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unused")
public class EventUtilsTest {

    // simple boolean cancel event
    public static class BoolEvent {
        private boolean canceled = false;
        public void setCanceled(boolean b) { this.canceled = b; }
        public boolean isCanceled() { return canceled; }
    }

    // boxed boolean and alternate name
    public static class BoxedEvent {
        private Boolean cancelled = Boolean.FALSE;
        public void setCancelled(Boolean b) { this.cancelled = b; }
        public Boolean isCancelled() { return cancelled; }
    }

    // enum result event
    public enum Result { ALLOW, DENY, CANCEL, FAIL }
    public static class EnumEvent {
        private Result result = Result.ALLOW;
        public void setResult(Result r) { this.result = r; }
        public Result getResult() { return result; }
    }

    @Test
    public void testBoolCancel() {
        BoolEvent e = new BoolEvent();
        assertFalse(e.isCanceled());
        boolean acted = EventUtils.tryCancel(e);
        assertTrue(acted);
        assertTrue(e.isCanceled());
    }

    @Test
    public void testBoxedCancel() {
        BoxedEvent e = new BoxedEvent();
        assertFalse(e.isCancelled());
        boolean acted = EventUtils.tryCancel(e);
        assertTrue(acted);
        assertTrue(e.isCancelled());
    }

    @Test
    public void testEnumCancel() {
        EnumEvent e = new EnumEvent();
        assertEquals(Result.ALLOW, e.getResult());
        boolean acted = EventUtils.tryCancel(e);
        assertTrue(acted);
        assertNotEquals(Result.ALLOW, e.getResult());
    }
}
