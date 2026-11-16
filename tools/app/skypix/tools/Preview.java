package app.skypix.tools;

import app.skypix.core.Cards;
import app.skypix.core.Fmt;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.GradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Dev tool: renders every widget through the real renderer so the art can be inspected without a device. */
public final class Preview {
    static final String FONT = System.getProperty("skypix.font", "Inter");

    static final int[][] TYPICAL = {
        {160, 118}, {340, 118}, {340, 118}, {340, 190}, {340, 118}, {160, 118}, {340, 118}, {340, 58}
    };
    static final int[][] MIN = {
        {110, 110}, {250, 110}, {250, 110}, {250, 180}, {250, 110}, {110, 110}, {250, 110}, {250, 56}
    };

    static BufferedImage render(int kind, int wDp, int hDp, float u, boolean dark, Cards.Data d) {
        AwtGfx g = new AwtGfx(Math.round(wDp * u), Math.round(hDp * u), FONT);
        Cards.render(g, kind, wDp, hDp, u, dark, d);
        g.dispose();
        return g.img;
    }

    static BufferedImage backdrop(int w, int h, boolean dark) {
        return backdrop(w, h, dark, false);
    }

    static BufferedImage backdrop(int w, int h, boolean dark, boolean colorful) {
        BufferedImage bg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (dark && colorful) {
            g.setPaint(new GradientPaint(0, 0, new Color(0x0B0F1A), w, h, new Color(0x2A1240)));
            g.fillRect(0, 0, w, h);
            blob(g, w * 0.2f, h * 0.25f, w * 0.5f, new Color(0x2E7DFF), 170);
            blob(g, w * 0.85f, h * 0.7f, w * 0.5f, new Color(0xFF4D8D), 150);
            blob(g, w * 0.5f, h * 0.95f, w * 0.45f, new Color(0x2EE6A6), 120);
        } else if (dark) {
            g.setPaint(Color.BLACK);
            g.fillRect(0, 0, w, h);
        } else {
            g.setPaint(new GradientPaint(0, 0, new Color(0xDCE8FA), w, h, new Color(0xF6E9DC)));
            g.fillRect(0, 0, w, h);
            blob(g, w * 0.15f, h * 0.2f, w * 0.4f, new Color(0xFFB6C1), 140);
            blob(g, w * 0.85f, h * 0.55f, w * 0.45f, new Color(0x9FD3FF), 140);
            blob(g, w * 0.4f, h * 0.95f, w * 0.4f, new Color(0xB8F0C8), 120);
        }
        g.dispose();
        return bg;
    }

    private static void blob(Graphics2D g, float cx, float cy, float r, Color c, int a) {
        Color c0 = new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
        Color c1 = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
        g.setPaint(new RadialGradientPaint(cx, cy, r, new float[]{0, 1}, new Color[]{c0, c1}));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
    }

    static void sheet(File out, int[] kinds, String scenario, int[][] sizes, float u) throws Exception {
        sheet(out, kinds, scenario, sizes, u, false);
    }

    static void sheet(File out, int[] kinds, String scenario, int[][] sizes, float u, boolean clear) throws Exception {
        float pad = 14;
        int colW = 0, totalH = 0;
        for (int k : kinds) {
            colW = Math.max(colW, sizes[k][0]);
            totalH += sizes[k][1] + (int) pad;
        }
        int W = Math.round((colW + pad * 2) * u) * 2, H = Math.round((totalH + pad) * u);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        for (int side = 0; side < 2; side++) {
            boolean dark = side == 1;
            int x0 = side * (W / 2);
            g.drawImage(backdrop(W / 2, H, dark, clear), x0, 0, null);
            Cards.Data d = Samples.data(scenario, false, false);
            d.transparentDark = clear;
            float y = pad;
            for (int k : kinds) {
                BufferedImage w = render(k, sizes[k][0], sizes[k][1], u, dark, d);
                g.drawImage(w, x0 + Math.round(pad * u), Math.round(y * u), null);
                y += sizes[k][1] + pad;
            }
        }
        g.dispose();
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
    }

    /** Writes the launcher + notification icons as vector drawables generated from the same pixel sprites. */
    static void icons(File dir) throws Exception {
        dir.mkdirs();
        int[] sprite = app.skypix.core.Sprites.get(app.skypix.core.Icon.PARTLY_DAY);
        writeVector(new File(dir, "ic_launcher_fg.xml"), sprite, 108, 28, 28, 3.25f, false);
        writeVector(new File(dir, "ic_launcher_mono.xml"), sprite, 108, 28, 28, 3.25f, true);
        writeVector(new File(dir, "ic_stat_weather.xml"), sprite, 24, 0, 0, 1.5f, true);
    }

    private static void writeVector(File out, int[] px, int size, float tx, float ty, float scale, boolean mono) throws Exception {
        java.util.LinkedHashMap<Integer, StringBuilder> paths = new java.util.LinkedHashMap<Integer, StringBuilder>();
        int n = app.skypix.core.Sprites.N;
        for (int y = 0; y < n; y++) {
            int x = 0;
            while (x < n) {
                int c = px[y * n + x];
                if ((c >>> 24) < 100) { x++; continue; }
                int run = 1;
                while (x + run < n && px[y * n + x + run] == c) run++;
                int key = mono ? 0xFFFFFFFF : c;
                StringBuilder sb = paths.get(key);
                if (sb == null) { sb = new StringBuilder(); paths.put(key, sb); }
                sb.append("M").append(x).append(",").append(y).append("h").append(run).append("v1h-").append(run).append("z");
                x += run;
            }
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        xml.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n");
        xml.append("    android:width=\"").append(size).append("dp\" android:height=\"").append(size).append("dp\"\n");
        xml.append("    android:viewportWidth=\"").append(size).append("\" android:viewportHeight=\"").append(size).append("\">\n");
        xml.append("    <group android:translateX=\"").append(tx).append("\" android:translateY=\"").append(ty)
           .append("\" android:scaleX=\"").append(scale).append("\" android:scaleY=\"").append(scale).append("\">\n");
        for (java.util.Map.Entry<Integer, StringBuilder> e : paths.entrySet()) {
            xml.append("        <path android:fillColor=\"#").append(String.format("%08X", e.getKey()))
               .append("\" android:pathData=\"").append(e.getValue()).append("\" />\n");
        }
        xml.append("    </group>\n</vector>\n");
        java.nio.file.Files.write(out.toPath(), xml.toString().getBytes("UTF-8"));
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 1 ? args[1] : "build/preview");
        String mode = args.length > 0 ? args[0] : "sheets";
        if (mode.equals("sheets")) {
            float u = 2.5f;
            sheet(new File(dir, "typical-a.png"), new int[]{0, 1, 2, 3}, "clear", TYPICAL, u);
            sheet(new File(dir, "typical-b.png"), new int[]{4, 5, 6, 7}, "clear", TYPICAL, u);
            sheet(new File(dir, "min-a.png"), new int[]{0, 1, 2, 3}, "clear", MIN, u);
            sheet(new File(dir, "min-b.png"), new int[]{4, 5, 6, 7}, "clear", MIN, u);
            sheet(new File(dir, "clear-a.png"), new int[]{0, 1, 2, 3}, "clear", TYPICAL, u, true);
            sheet(new File(dir, "clear-b.png"), new int[]{4, 5, 6, 7}, "clear", TYPICAL, u, true);
            String[] moods = {"night", "rain", "storm", "snow", "cloudy", "fog", "partly"};
            for (String m : moods) {
                sheet(new File(dir, "mood-" + m + ".png"), new int[]{1, 2, 7}, m, TYPICAL, u);
            }
        } else if (mode.equals("anim")) {
            String[] sc = {"clear", "night", "rain", "stormnow", "snow", "cloudy", "fog"};
            float[] ts = {0.9f, 1.15f, 2.4f};
            float u = 2f;
            int cw = 340, ch = 118;
            BufferedImage img = new BufferedImage(Math.round((cw + 14) * u) * ts.length, Math.round((ch + 14) * u) * sc.length, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.drawImage(backdrop(img.getWidth(), img.getHeight(), true, true), 0, 0, null);
            for (int r = 0; r < sc.length; r++) {
                for (int c = 0; c < ts.length; c++) {
                    Cards.Data d = Samples.data(sc[r], false, false);
                    d.anim = ts[c];
                    BufferedImage w = render(Cards.NOW_WIDE, cw, ch, u, true, d);
                    g.drawImage(w, Math.round((7 + c * (cw + 14)) * u), Math.round((7 + r * (ch + 14)) * u), null);
                }
            }
            g.dispose();
            dir.mkdirs();
            ImageIO.write(img, "png", new File(dir, "anim.png"));
        } else if (mode.equals("icons")) {
            icons(dir);
        } else if (mode.equals("headsup")) {
            float u = 2.4f;
            int[][] sz = {{110, 110}, {110, 180}, {180, 180}, {250, 40}, {180, 40}};
            String[] sc = {"clear", "rain"};
            BufferedImage img = new BufferedImage(Math.round(900 * u), Math.round(420 * u), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.drawImage(backdrop(img.getWidth(), img.getHeight(), true, true), 0, 0, null);
            for (int r = 0; r < sc.length; r++) {
                Cards.Data d = Samples.data(sc[r], false, false);
                int x = 14;
                for (int c = 0; c < sz.length; c++) {
                    g.drawImage(render(Cards.HEADSUP, sz[c][0], sz[c][1], u, true, d), Math.round(x * u), Math.round((14 + r * 200) * u), null);
                    x += sz[c][0] + 14;
                }
            }
            g.dispose();
            dir.mkdirs();
            ImageIO.write(img, "png", new File(dir, "headsup.png"));
        } else if (mode.equals("clocks")) {
            int[] kinds = {Cards.CLOCK_WORD, Cards.CLOCK_SKY, Cards.CLOCK_SKY, Cards.CLOCK_DOTS, Cards.CLOCK_PILL, Cards.CLOCK_PILL, Cards.CLOCK_RINGS, Cards.CLOCK_SCHEDULE, Cards.CLOCK_WORLD};
            int[][] sz = {{250, 180}, {250, 110}, {110, 110}, {250, 110}, {250, 110}, {110, 110}, {110, 110}, {250, 110}, {250, 180}};
            float u = 1.7f;
            int perRow = 4;
            String[] times = {"2026-11-16T10:10", "2026-11-16T18:05", "2026-11-16T22:10"};
            for (int side = 0; side < 2; side++) {
                boolean dark = side == 1;
                int cols = 3, rowsN = (kinds.length + cols - 1) / cols;
                int W = 14 + cols * (250 + 14), H = 14;
                int[] rowH = new int[rowsN];
                for (int i = 0; i < kinds.length; i++) rowH[i / cols] = Math.max(rowH[i / cols], sz[i][1]);
                for (int hh : rowH) H += hh + 14;
                BufferedImage img = new BufferedImage(Math.round(W * u), Math.round(H * u), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.drawImage(backdrop(img.getWidth(), img.getHeight(), dark, dark), 0, 0, null);
                int y = 14;
                for (int rI = 0; rI < rowsN; rI++) {
                    int x = 14;
                    for (int c = 0; c < cols && rI * cols + c < kinds.length; c++) {
                        int i = rI * cols + c;
                        Cards.Data d = Samples.data(dark ? "night" : "clear", false, false);
                        d.nowSec = Fmt.parseLocal(times[(i + side) % 3], 8 * 3600);
                        g.drawImage(render(kinds[i], sz[i][0], sz[i][1], u, dark, d), Math.round(x * u), Math.round(y * u), null);
                        x += 250 + 14;
                    }
                    y += rowH[rI] + 14;
                }
                g.dispose();
                dir.mkdirs();
                ImageIO.write(img, "png", new File(dir, dark ? "clocks-dark.png" : "clocks-light.png"));
            }
        } else if (mode.equals("you")) {
            int[] kinds = {Cards.NOW, Cards.CLASSIC, Cards.BIG, Cards.HEADSUP, Cards.HOURLY};
            int[][] sz = {{110, 110}, {110, 110}, {110, 110}, {180, 110}, {250, 110}};
            float u = 2.4f;
            BufferedImage img = new BufferedImage(Math.round(820 * u), Math.round(2 * 130 * u), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.drawImage(backdrop(img.getWidth(), img.getHeight(), true, true), 0, 0, null);
            for (int side = 0; side < 2; side++) {
                Cards.Data d = Samples.data("clear", false, false);
                d.youTop = 0xFF6750A4; d.youBottom = 0xFF4F378B; d.youP0 = 0xFF7F67BE; d.youP1 = 0xFF4F378B;
                int x = 14;
                for (int c = 0; c < kinds.length; c++) {
                    g.drawImage(render(kinds[c], sz[c][0], sz[c][1], u, side == 1, d), Math.round(x * u), Math.round((10 + side * 130) * u), null);
                    x += sz[c][0] + 14;
                }
            }
            g.dispose();
            dir.mkdirs();
            ImageIO.write(img, "png", new File(dir, "you.png"));
        } else if (mode.equals("rainsoon")) {
            String[] sc = {"clear", "rain", "stormnow"};
            float u = 2.4f;
            BufferedImage img = new BufferedImage(Math.round(560 * u), Math.round(3 * 130 * u), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.drawImage(backdrop(img.getWidth(), img.getHeight(), true, true), 0, 0, null);
            for (int r = 0; r < sc.length; r++) {
                Cards.Data d = Samples.data(sc[r], false, false);
                g.drawImage(render(Cards.RAIN_SOON, 110, 110, u, true, d), Math.round(14 * u), Math.round((10 + r * 130) * u), null);
                g.drawImage(render(Cards.RAIN_SOON, 250, 110, u, false, d), Math.round(140 * u), Math.round((10 + r * 130) * u), null);
            }
            g.dispose();
            dir.mkdirs();
            ImageIO.write(img, "png", new File(dir, "rainsoon.png"));
        } else if (mode.equals("cats")) {
            app.skypix.core.Icon[] all = app.skypix.core.Icon.values();
            int cols = 7, cell = 150, rows = (all.length + cols - 1) / cols;
            for (int side = 0; side < 2; side++) {
                AwtGfx g = new AwtGfx(cols * cell, rows * (cell + 26), "SansSerif");
                java.awt.Graphics2D g2 = g.img.createGraphics();
                g2.setColor(side == 0 ? new java.awt.Color(0xFFFFFF) : new java.awt.Color(0x000000));
                g2.fillRect(0, 0, g.img.getWidth(), g.img.getHeight());
                for (int i = 0; i < all.length; i++) {
                    float x = (i % cols) * cell + 15, y = (i / cols) * (cell + 26) + 6;
                    app.skypix.core.Glyphs.draw(g, all[i], x, y, cell - 30, 0.6f, 0, side == 0);
                    g2.setColor(side == 0 ? java.awt.Color.DARK_GRAY : java.awt.Color.LIGHT_GRAY);
                    g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 14));
                    g2.drawString(all[i].name(), x, y + cell - 14);
                }
                g2.dispose();
                dir.mkdirs();
                ImageIO.write(g.img, "png", new File(dir, side == 0 ? "cats-light.png" : "cats-dark.png"));
            }
        } else if (mode.equals("news")) {
            String[] sc = {"clear", "partly", "rain", "stormnow", "snow", "windy", "night"};
            for (String name : sc) {
                Cards.Data d = Samples.data(name, false, false);
                app.skypix.core.CatNews.Bulletin n = app.skypix.core.CatNews.news(d.weather, d.units, d.place.shortName(), d.nowSec);
                System.out.println("==== " + name + " NEWS\n" + n.title + "\n" + n.text + "\n--\n" + n.big + "\n");
                for (app.skypix.core.Insights.Insight in : app.skypix.core.Insights.compute(d.weather, d.units, d.nowSec)) {
                    if (in.sev >= app.skypix.core.Insights.NOTICE) System.out.println("  ALERT[" + in.sev + "] " + app.skypix.core.CatNews.alertTitle(in) + " | " + app.skypix.core.CatNews.alertBody(in).replace("\n", " / "));
                }
                app.skypix.core.CatNews.Bulletin m = app.skypix.core.CatNews.morning(d.weather, d.units, d.place.shortName(), d.nowSec);
                System.out.println("---- " + name + " MORNING\n" + m.title + "\n" + m.text + "\n--\n" + m.big + "\n");
            }
        } else if (mode.equals("iconsheet")) {
            String[] sc = {"clear", "night", "partly", "cloudy", "fog", "rain", "stormnow", "snow", "windy"};
            int[] ks = {0, 1, 2};
            int[][] sz = {{56, 56}, {150, 56}, {110, 110}};
            float u = 2.2f;
            for (int side = 0; side < 2; side++) {
                boolean dark = side == 1;
                int W = 340, H = 14 + sc.length * 124;
                BufferedImage img = new BufferedImage(Math.round(W * u), Math.round(H * u), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.drawImage(backdrop(img.getWidth(), img.getHeight(), dark, dark), 0, 0, null);
                for (int r = 0; r < sc.length; r++) {
                    Cards.Data d = Samples.data(sc[r], false, false);
                    int x = 14;
                    for (int c = 0; c < ks.length; c++) {
                        BufferedImage w = render(Cards.ICON, sz[c][0], sz[c][1], u, dark, d);
                        g.drawImage(w, Math.round(x * u), Math.round((14 + r * 124) * u), null);
                        x += sz[c][0] + 18;
                    }
                }
                g.dispose();
                dir.mkdirs();
                ImageIO.write(img, "png", new File(dir, dark ? "icon-dark.png" : "icon-light.png"));
            }
        } else if (mode.equals("variants")) {
            for (int side = 0; side < 2; side++) {
                boolean dark = side == 1;
                float u = 1.6f;
                int W = 1180, H = 1500;
                BufferedImage img = new BufferedImage(Math.round(W * u), Math.round(H * u), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.drawImage(backdrop(img.getWidth(), img.getHeight(), dark, false), 0, 0, null);
                Cards.Data d = Samples.data("clear", false, false);
                int x = 14, y = 14, rowH = 0;
                for (int v = 0; v < app.skypix.core.Variants.COUNT; v++) {
                    int vw = app.skypix.core.Variants.widthDp(v), vh = app.skypix.core.Variants.heightDp(v);
                    if (x + vw > W - 14) { x = 14; y += rowH + 14; rowH = 0; }
                    g.drawImage(render(app.skypix.core.Variants.KIND[v], vw, vh, u, dark, d), Math.round(x * u), Math.round(y * u), null);
                    x += vw + 14;
                    rowH = Math.max(rowH, vh);
                }
                g.dispose();
                dir.mkdirs();
                ImageIO.write(img, "png", new File(dir, dark ? "variants-dark.png" : "variants-light.png"));
            }
        } else if (mode.equals("previews")) {
            Cards.Data d = Samples.data("clear", false, false);
            dir.mkdirs();
            for (int v = 0; v < app.skypix.core.Variants.COUNT; v++) {
                BufferedImage img = render(app.skypix.core.Variants.KIND[v], app.skypix.core.Variants.widthDp(v), app.skypix.core.Variants.heightDp(v), 2f, false, d);
                ImageIO.write(img, "png", new File(dir, "preview_" + app.skypix.core.Variants.KEY[v] + ".png"));
            }
        }
    }
}
