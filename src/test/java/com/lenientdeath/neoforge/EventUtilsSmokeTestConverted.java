package com.lenientdeath.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unused")
public class EventUtilsSmokeTestConverted {

    // simple boolean cancel event
    public static class BoolEvent {
        private boolean canceled = false;
        public void setCanceled(boolean b) { this.canceled = b; }
        public boolean isCanceled() { return canceled; }
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
        BoolEvent be = new BoolEvent();
        assertFalse(be.isCanceled(), "BoolEvent should start not canceled");
        boolean acted = EventUtils.tryCancel(be);
        assertTrue(acted, "EventUtils.tryCancel should act on BoolEvent");
        assertTrue(be.isCanceled(), "BoolEvent should be canceled after tryCancel");
    }

    @Test
    public void testEnumCancel() {
        EnumEvent ee = new EnumEvent();
        assertEquals(Result.ALLOW, ee.getResult(), "EnumEvent should start with ALLOW");
        boolean acted = EventUtils.tryCancel(ee);
        assertTrue(acted, "EventUtils.tryCancel should act on EnumEvent");
        assertNotEquals(Result.ALLOW, ee.getResult(), "EnumEvent result should not remain ALLOW after tryCancel");
    }
}
