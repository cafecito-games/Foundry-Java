package games.cafecito.foundry.generator;

/** Single-line diagnostic rendering for realization and parity failures. */
final class Diagnostics {
    private Diagnostics() {}

    /**
     * Escapes backslashes, controls, and line or paragraph separators so a diagnostic value can
     * never break a failure out of one line.
     */
    static String escape(String value) {
        if (value == null) {
            return "<null>";
        }
        StringBuilder escaped = new StringBuilder();
        value.codePoints()
                .forEach(
                        codePoint -> {
                            if (codePoint == '\\') {
                                escaped.append("\\\\");
                            } else if (codePoint == '\n') {
                                escaped.append("\\n");
                            } else if (codePoint == '\r') {
                                escaped.append("\\r");
                            } else if (codePoint == '\t') {
                                escaped.append("\\t");
                            } else if (Character.isISOControl(codePoint)
                                    || Character.getType(codePoint) == Character.LINE_SEPARATOR
                                    || Character.getType(codePoint)
                                            == Character.PARAGRAPH_SEPARATOR) {
                                escaped.append(String.format("\\u%04x", codePoint));
                            } else {
                                escaped.appendCodePoint(codePoint);
                            }
                        });
        return escaped.toString();
    }
}
