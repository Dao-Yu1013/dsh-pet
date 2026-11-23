package javax.lang.model;

/** Minimal JSR-269 compatibility enum for ECJ on Android, whose runtime omits this JDK API. */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5, RELEASE_6,
    RELEASE_7, RELEASE_8, RELEASE_9, RELEASE_10, RELEASE_11, RELEASE_12, RELEASE_13,
    RELEASE_14, RELEASE_15, RELEASE_16, RELEASE_17;

    public static SourceVersion latest() { return RELEASE_17; }
    public static SourceVersion latestSupported() { return RELEASE_17; }

    public static boolean isIdentifier(CharSequence name) {
        if (name == null || name.length() == 0) return false;
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
    }

    public static boolean isName(CharSequence name) {
        if (name == null || name.length() == 0) return false;
        int start = 0;
        while (start < name.length()) {
            int end = start;
            while (end < name.length() && name.charAt(end) != '.') end++;
            if (end == start || !isIdentifier(name.subSequence(start, end))) return false;
            start = end + 1;
        }
        return name.charAt(name.length() - 1) != '.';
    }

    public static boolean isKeyword(CharSequence word) {
        return isKeyword(word, latestSupported());
    }

    public static boolean isKeyword(CharSequence word, SourceVersion version) {
        if (word == null) return false;
        String s = word.toString();
        String keywords = " abstract assert boolean break byte case catch char class const continue default "
                + "do double else enum extends final finally float for goto if implements import "
                + "instanceof int interface long native new package private protected public return "
                + "short static strictfp super switch synchronized this throw throws transient try "
                + "void volatile while true false null _ ";
        return keywords.contains(" " + s + " ");
    }
}
