package ru.rtk.crm.report;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

@Component
public class StatisticsChartWriter {
    private static final int WIDTH = 1200;
    private static final int PAD = 24;
    private static final int LABEL_WIDTH = 420;
    private static final int BAR_LEFT = PAD + LABEL_WIDTH + 16;
    private static final int BAR_RIGHT = WIDTH - PAD - 80;
    private static final float TITLE_SIZE = 20f;
    private static final float CAPTION_SIZE = 13f;
    private static final float TEXT_SIZE = 14f;
    private static final int LINE_HEIGHT = 19;
    private static final int MAX_LABEL_LINES = 3;
    private static final int AXIS_HEIGHT = 52;
    private static final int ROW_PADDING = 12;
    private static final int BAR_HEIGHT = 18;
    private static final int EMPTY_HEIGHT = 40;
    private static final int PDF_SCALE = 2;
    private static final String ELLIPSIS = "…";
    private static final Color BAR = new Color(0x0f65d8);
    private static final Color UNSPECIFIED_FILL = new Color(0xe7ecf3);
    private static final Color UNSPECIFIED_LINE = new Color(0x50627a);
    private static final Color TEXT = new Color(0x17202f);
    private static final Color MUTED = new Color(0x50627a);
    private static final Color GRID = new Color(0xd7dfeb);
    private static final FontRenderContext MEASURE = new FontRenderContext(null, true, true);

    private Font loadedFont;

    public void writePng(StatisticsResult statistics, OutputStream output) throws IOException {
        Chart chart = chart(statistics);
        Font font = font();
        List<Row> rows = rows(font, chart.bars());
        Font titleFont = font.deriveFont(TITLE_SIZE);
        Font captionFont = font.deriveFont(CAPTION_SIZE);
        List<String> title = wrap(chart.title(), titleFont, WIDTH - 2 * PAD);
        List<String> caption = new ArrayList<>();
        for (String line : chart.caption()) {
            caption.addAll(wrap(line, captionFont, WIDTH - 2 * PAD));
        }
        int titleLine = Math.round(TITLE_SIZE * 1.4f);
        int captionLine = Math.round(CAPTION_SIZE * 1.4f);
        int bodyTop = PAD + title.size() * titleLine + 8 + caption.size() * captionLine + 16;
        BufferedImage image = new BufferedImage(WIDTH, bodyTop + bodyHeight(rows) + PAD, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = graphics(image, 1);
        try {
            int top = drawLines(graphics, title, titleFont, TEXT, PAD, titleLine);
            drawLines(graphics, caption, captionFont, MUTED, top + 8, captionLine);
            drawBody(graphics, font, chart, rows, bodyTop);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG image writer is unavailable");
        }
    }

    public void writePdf(StatisticsResult statistics, OutputStream output) throws IOException {
        Chart chart = chart(statistics);
        Font font = font();
        List<Row> rows = rows(font, chart.bars());
        PdfLayout.write(chart.title(), statistics.generatedAt(), chart.caption(), output, layout -> {
            float pointsPerPixel = layout.contentWidth() / WIDTH;
            layout.gap(PdfLayout.NOTE_SIZE);
            List<Row> rest = rows;
            do {
                int count = fitting(rest, layout.available() / pointsPerPixel);
                if (count == 0 && (!rest.isEmpty() || layout.available() < bodyHeight(rest) * pointsPerPixel)) {
                    layout.newPage();
                    count = Math.min(rest.size(), Math.max(1, fitting(rest, layout.available() / pointsPerPixel)));
                }
                List<Row> part = rest.subList(0, count);
                layout.image(bodyImage(font, chart, part), bodyHeight(part) * pointsPerPixel);
                rest = rest.subList(count, rest.size());
            } while (!rest.isEmpty());
            layout.gap(PdfLayout.NOTE_SIZE);
            layout.paragraph("Таблица основания диаграммы", PdfLayout.NOTE_SIZE);
            layout.table(List.of(statistics.groupBy().header(statistics.kind()), statistics.kind().countTitle()), new int[]{4, 1});
            for (Bar bar : chart.bars()) {
                layout.row(List.of(bar.label(), Long.toString(bar.count())));
            }
            layout.row(List.of("Всего строк в выборке", Long.toString(statistics.total())));
        });
    }

    private static List<String> caption(StatisticsResult statistics) {
        List<String> lines = new ArrayList<>();
        lines.add("Показатель: " + statistics.kind().countTitle().toLowerCase(Locale.ROOT)
                + " в группе; всего в выборке: " + statistics.total() + "; шкала начинается с нуля");
        if (statistics.groupBy() == StatisticsGroupBy.PRODUCT) {
            lines.add("Строка с несколькими ИТ-продуктами учтена в каждом из них, поэтому сумма столбцов может быть больше итога");
        }
        if (statistics.groupBy().mayBeUnspecified()) {
            lines.add("«" + ReportColumn.UNSPECIFIED + "» (штриховка) — строки без значения группировки: "
                    + statistics.unknownCount() + "; это не нулевое значение");
        }
        if (statistics.groupBy() == StatisticsGroupBy.MONTH) {
            lines.add((switch (statistics.kind()) {
                case EVENTS -> "Месяц события";
                case PORTFOLIO -> "Месяц создания взаимодействия";
                case DEMAND -> "Месяц подачи заявки";
            })
                    + " по московскому времени; месяцы периода без строк показаны нулём");
        }
        lines.addAll(statistics.notes());
        return lines;
    }

    private static Chart chart(StatisticsResult statistics) {
        List<Bar> bars = new ArrayList<>();
        for (StatisticsResult.Item item : statistics.items()) {
            bars.add(new Bar(item.label(), item.count(), false));
        }
        if (statistics.groupBy().mayBeUnspecified()) {
            bars.add(new Bar(ReportColumn.UNSPECIFIED, statistics.unknownCount(), true));
        }
        if (bars.size() > StatisticsResult.MAX_CHART_BARS) {
            throw ReportException.chartLimit(StatisticsResult.MAX_CHART_BARS);
        }
        long max = bars.stream().mapToLong(Bar::count).max().orElse(0);
        long step = step(max);
        return new Chart(
                statistics.kind().title() + " — " + statistics.groupBy().title(),
                caption(statistics),
                statistics.kind().countTitle() + ", шкала от нуля",
                bars,
                Math.max(step, (max + step - 1) / step * step),
                step
        );
    }

    private static long step(long max) {
        long raw = Math.max(1, (max + 4) / 5);
        long magnitude = 1;
        while (magnitude * 10 <= raw) {
            magnitude *= 10;
        }
        for (long factor : new long[]{1, 2, 5}) {
            if (factor * magnitude >= raw) {
                return factor * magnitude;
            }
        }
        return 10 * magnitude;
    }

    private synchronized Font font() throws IOException {
        if (loadedFont != null) {
            return loadedFont;
        }
        try (InputStream data = StatisticsChartWriter.class.getResourceAsStream(PdfLayout.FONT_RESOURCE)) {
            if (data == null) {
                throw new IOException("Chart font resource is unavailable");
            }
            loadedFont = Font.createFont(Font.TRUETYPE_FONT, data);
            return loadedFont;
        } catch (FontFormatException exception) {
            throw new IOException("Chart font resource cannot be read", exception);
        }
    }

    private static List<Row> rows(Font font, List<Bar> bars) {
        Font labelFont = font.deriveFont(TEXT_SIZE);
        List<Row> rows = new ArrayList<>();
        for (Bar bar : bars) {
            List<String> lines = wrap(bar.label(), labelFont, LABEL_WIDTH);
            if (lines.size() > MAX_LABEL_LINES) {
                String last = lines.get(MAX_LABEL_LINES - 1) + " " + String.join(" ", lines.subList(MAX_LABEL_LINES, lines.size()));
                lines = new ArrayList<>(lines.subList(0, MAX_LABEL_LINES - 1));
                lines.add(ellipsize(last, labelFont, LABEL_WIDTH));
            }
            rows.add(new Row(bar, lines, Math.max(lines.size() * LINE_HEIGHT, BAR_HEIGHT) + ROW_PADDING));
        }
        return rows;
    }

    private static int bodyHeight(List<Row> rows) {
        return AXIS_HEIGHT + (rows.isEmpty() ? EMPTY_HEIGHT : rows.stream().mapToInt(Row::height).sum());
    }

    private static int fitting(List<Row> rows, float availablePixels) {
        int used = AXIS_HEIGHT;
        int count = 0;
        for (Row row : rows) {
            if (used + row.height() > availablePixels) {
                break;
            }
            used += row.height();
            count++;
        }
        return count;
    }

    private static BufferedImage bodyImage(Font font, Chart chart, List<Row> rows) {
        BufferedImage image = new BufferedImage(WIDTH * PDF_SCALE, bodyHeight(rows) * PDF_SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = graphics(image, PDF_SCALE);
        try {
            drawBody(graphics, font, chart, rows, 0);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static Graphics2D graphics(BufferedImage image, int scale) {
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.scale(scale, scale);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return graphics;
    }

    private static void drawBody(Graphics2D graphics, Font font, Chart chart, List<Row> rows, int top) {
        Font textFont = font.deriveFont(TEXT_SIZE);
        graphics.setFont(textFont);
        int gridTop = top + AXIS_HEIGHT;
        int gridBottom = top + bodyHeight(rows);
        graphics.setColor(MUTED);
        graphics.drawString(chart.axisTitle(), BAR_LEFT, top + 18);
        graphics.setStroke(new BasicStroke(1f));
        for (long tick = 0; tick <= chart.axisMax(); tick += chart.step()) {
            int x = x(tick, chart.axisMax());
            graphics.setColor(tick == 0 ? MUTED : GRID);
            graphics.drawLine(x, gridTop - 6, x, gridBottom);
            String label = Long.toString(tick);
            graphics.setColor(MUTED);
            graphics.drawString(label, x - width(label, textFont) / 2, gridTop - 12);
        }
        if (rows.isEmpty()) {
            graphics.setColor(MUTED);
            graphics.drawString("Нет строк, удовлетворяющих фильтрам", BAR_LEFT + 8, gridTop + 26);
            return;
        }
        int y = gridTop;
        for (Row row : rows) {
            Bar bar = row.bar();
            int textTop = y + (row.height() - row.lines().size() * LINE_HEIGHT) / 2;
            graphics.setColor(bar.unspecified() ? MUTED : TEXT);
            for (int index = 0; index < row.lines().size(); index++) {
                graphics.drawString(row.lines().get(index), PAD, textTop + index * LINE_HEIGHT + 14);
            }
            int barTop = y + (row.height() - BAR_HEIGHT) / 2;
            int barWidth = x(bar.count(), chart.axisMax()) - BAR_LEFT;
            if (barWidth > 0 && bar.unspecified()) {
                graphics.setPaint(hatch());
                graphics.fillRect(BAR_LEFT, barTop, barWidth, BAR_HEIGHT);
                graphics.setColor(UNSPECIFIED_LINE);
                graphics.drawRect(BAR_LEFT, barTop, barWidth, BAR_HEIGHT);
            } else if (barWidth > 0) {
                graphics.setColor(BAR);
                graphics.fillRect(BAR_LEFT, barTop, barWidth, BAR_HEIGHT);
            }
            graphics.setColor(TEXT);
            graphics.drawString(Long.toString(bar.count()), BAR_LEFT + barWidth + 8, barTop + 14);
            y += row.height();
        }
    }

    private static int drawLines(Graphics2D graphics, List<String> lines, Font font, Color color, int top, int lineHeight) {
        graphics.setFont(font);
        graphics.setColor(color);
        int y = top;
        for (String line : lines) {
            graphics.drawString(line, PAD, y + font.getSize2D());
            y += lineHeight;
        }
        return y;
    }

    private static TexturePaint hatch() {
        BufferedImage tile = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = tile.createGraphics();
        try {
            graphics.setColor(UNSPECIFIED_FILL);
            graphics.fillRect(0, 0, 8, 8);
            graphics.setColor(UNSPECIFIED_LINE);
            graphics.drawLine(0, 7, 7, 0);
        } finally {
            graphics.dispose();
        }
        return new TexturePaint(tile, new Rectangle(0, 0, 8, 8));
    }

    private static int x(long value, long axisMax) {
        return BAR_LEFT + (int) Math.round((double) value * (BAR_RIGHT - BAR_LEFT) / axisMax);
    }

    private static List<String> wrap(String value, Font font, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : sanitize(value, font).split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(candidate, font) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            String rest = word;
            while (width(rest, font) > maxWidth) {
                int cut = fittingPrefix(rest, font, maxWidth);
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        if (!line.isEmpty() || lines.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String ellipsize(String value, Font font, int maxWidth) {
        if (width(value, font) <= maxWidth) {
            return value;
        }
        return value.substring(0, fittingPrefix(value, font, maxWidth - width(ELLIPSIS, font))) + ELLIPSIS;
    }

    private static int fittingPrefix(String value, Font font, int maxWidth) {
        int end = Character.charCount(value.codePointAt(0));
        while (end < value.length()) {
            int next = end + Character.charCount(value.codePointAt(end));
            if (width(value.substring(0, next), font) > maxWidth) {
                break;
            }
            end = next;
        }
        return end;
    }

    private static String sanitize(String value, Font font) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                result.append(' ');
            } else if (!font.canDisplay(codePoint)) {
                result.append('?');
            } else {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    private static int width(String value, Font font) {
        return (int) Math.ceil(font.getStringBounds(value, MEASURE).getWidth());
    }

    private record Chart(String title, List<String> caption, String axisTitle, List<Bar> bars, long axisMax, long step) {
    }

    private record Bar(String label, long count, boolean unspecified) {
    }

    private record Row(Bar bar, List<String> lines, int height) {
    }
}
