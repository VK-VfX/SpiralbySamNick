package app.skypix.core;

/**
 * Small-label text helper. (Historical name: labels used to be drawn with a 5x7 pixel font.)
 * A "cell" is 1/7 of the label's cap height, so layouts written for the pixel font keep working.
 */
public final class PixelFont {
    private PixelFont() {}

    public static final int H = 7;

    /** Text size (px) for a given "cell": cap height of the text equals H cells. */
    private static float size(float cell) {
        return cell * 9.9f;
    }

    /** Tidies the old ALL-CAPS labels into sentence case: "SUNRISE" becomes "Sunrise". */
    static String soft(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                int j = i;
                while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
                String w = s.substring(i, j);
                boolean caps = true;
                for (int k = 0; k < w.length(); k++) if (!Character.isUpperCase(w.charAt(k))) caps = false;
                out.append(caps && w.length() >= 3 ? w.charAt(0) + w.substring(1).toLowerCase() : w);
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    public static float width(Gfx g, String s, float cell) {
        return g.textWidth(soft(s), size(cell), Gfx.FONT_MEDIUM);
    }

    public static float height(float cell) {
        return H * cell;
    }

    public static void draw(Gfx g, String s, float x, float y, float cell, int align, int argb) {
        g.text(soft(s), x, y + H * cell, size(cell), Gfx.FONT_MEDIUM, align, argb);
    }
}
