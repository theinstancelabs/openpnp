package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/** Result of the pre-flight interlock check. */
public class InterlockReport {
    public static class Violation {
        /** I3, I4, I5, I7, I9, PLAN, CONFIG, MACHINE */
        public final String code;
        public final String message;

        public Violation(String code, String message) {
            this.code = code;
            this.message = message;
        }

        @Override
        public String toString() {
            return code + ": " + message;
        }
    }

    public final List<Violation> violations = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();

    public boolean ok() {
        return violations.isEmpty();
    }

    public InterlockReport add(String code, String message) {
        violations.add(new Violation(code, message));
        return this;
    }

    public boolean has(String code) {
        for (Violation v : violations) {
            if (v.code.equals(code)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return ok() ? "interlocks OK" : "interlocks refused: " + violations;
    }
}
