package com.diafarms.ml.commons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TelephoneTest {

    @Test
    void acceptés() {
        assertEquals("+223 83 91 86 99", Telephone.verifier("  +223 83 91 86 99 "));
        assertEquals("76000000", Telephone.verifier("76000000"));
        assertEquals("07 00 00 00 00", Telephone.verifier("07 00 00 00 00"));
        assertNull(Telephone.verifier("   "));
        assertNull(Telephone.verifier(null));
    }

    @Test
    void refusés() {
        for (String t : new String[] {"76 AB 00 00", "+223-76-00", "76.00.00.00", "223+76", "++223 76", "+", "abc", "(223) 76"}) {
            assertThrows(IllegalArgumentException.class, () -> Telephone.verifier(t), t);
        }
    }
}
