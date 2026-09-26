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
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

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
    private static final int PLOT_LEFT = PAD + 72;
    private static final int PLOT_RIGHT = WIDTH - PAD - 24;
    private static final int PLOT_HEIGHT = 380;
    private static final int PLOT_TITLE_HEIGHT = 32;
    private static final int MONTH_LABELS_HEIGHT = 44;
    private static final int MONTH_LABEL_SPACE = 72;
    private static final int LEGEND_ROW = 22;
    private static final int MAX_POINT_LABELS = 60;
    private static final int PDF_SCALE = 2;
    private static final String ELLIPSIS = "…";
    private static final Color BAR = new Color(0x0f65d8);
    private static final Color UNSPECIFIED_FILL = new Color(0xe7ecf3);
    private static final Color UNSPECIFIED_LINE = new Color(0x50627a);
    private static final Color TEXT = new Color(0x17202f);
    private static final Color MUTED = new Color(0x50627a);
    private static final Color GRID = new Color(0xd7dfeb);
    private static final Color[] SERIES_COLORS = {
            new Color(0x0f65d8), new Color(0xc2410c), new Color(0x15803d), new Color(0x7e22ce), new Color(0x0e7490)
    };
    private static final DateTimeFormatter AXIS_MONTH = DateTimeFormatter.ofPattern("MM.yyyy");
    private static final FontRenderContext MEASURE = new FontRenderContext(null, true, true);

    private Font loadedFont;

    public void writePng(StatisticsResult statistics, boolean line, OutputStream output) throws IOException {
        Font font = font();
        if (line) {
            LineChart chart = lineChart(statistics);
            png(font, chart.title(), chart.caption(), lineBodyHeight(chart), graphics -> drawLineBody(graphics, font, chart), output);
            return;
        }
        Chart chart = chart(statistics);
        List<Row> rows = rows(font, chart.bars());
        png(font, chart.title(), chart.caption(), bodyHeight(rows), graphics -> drawBody(graphics, font, chart, rows), output);
    }

    public void writePdf(StatisticsResult statistics, boolean line, OutputStream output) throws IOException {
        if (line) {
            writeLinePdf(statistics, output);
            return;
        }
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
                layout.image(scaledImage(bodyHeight(part), graphics -> drawBody(graphics, font, chart, part)),
                        bodyHeight(part) * pointsPerPixel);
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

    private void writeLinePdf(StatisticsResult statistics, OutputStream output) throws IOException {
        LineChart chart = lineChart(statistics);
        Font font = font();
        PdfLayout.write(chart.title(), statistics.generatedAt(), chart.caption(), output, layout -> {
            float height = lineBodyHeight(chart) * layout.contentWidth() / WIDTH;
            layout.gap(PdfLayout.NOTE_SIZE);
            if (layout.available() < height) {
                layout.newPage();
            }
            layout.image(scaledImage(lineBodyHeight(chart), graphics -> drawLineBody(graphics, font, chart)), height);
            layout.gap(PdfLayout.NOTE_SIZE);
            layout.paragraph("Таблица основания графика", PdfLayout.NOTE_SIZE);
            boolean series = statistics.seriesBy() != null;
            List<String> headers = new ArrayList<>(List.of(StatisticsGroupBy.MONTH.header(statistics.kind())));
            chart.lines().forEach(line -> headers.add(line.label()));
            if (series) {
                headers.add("Всего за месяц");
            }
            int[] weights = new int[headers.size()];
            Arrays.fill(weights, 2);
            weights[0] = 3;
            layout.table(headers, weights);
            for (int index = 0; index < statistics.items().size(); index++) {
                StatisticsResult.Item item = statistics.items().get(index);
                List<String> cells = new ArrayList<>(List.of(item.label()));
                for (Line line : chart.lines()) {
                    cells.add(Long.toString(line.values().get(index)));
                }
                if (series) {
                    cells.add(Long.toString(item.count()));
                }
                layout.row(cells);
            }
            if (series) {
                List<String> totals = new ArrayList<>(List.of("Итого за период"));
                for (Line line : chart.lines()) {
                    totals.add(Long.toString(line.values().stream().mapToLong(Long::longValue).sum()));
                }
                totals.add(Long.toString(statistics.items().stream().mapToLong(StatisticsResult.Item::count).sum()));
                layout.row(totals);
            }
            List<String> total = new ArrayList<>(List.of("Всего строк в выборке"));
            for (int index = 1; index < headers.size() - 1; index++) {
                total.add("");
            }
            total.add(Long.toString(statistics.total()));
            layout.row(total);
        });
    }

    private static void png(
            Font font,
            String titleText,
            List<String> captionLines,
            int bodyHeight,
            Consumer<Graphics2D> body,
            OutputStream output
    ) throws IOException {
        Font titleFont = font.deriveFont(TITLE_SIZE);
        Font captionFont = font.deriveFont(CAPTION_SIZE);
        List<String> title = wrap(titleText, titleFont, WIDTH - 2 * PAD);
        List<String> caption = new ArrayList<>();
        for (String line : captionLines) {
            caption.addAll(wrap(line, captionFont, WIDTH - 2 * PAD));
        }
        int titleLine = Math.round(TITLE_SIZE * 1.4f);
        int captionLine = Math.round(CAPTION_SIZE * 1.4f);
        int bodyTop = PAD + title.size() * titleLine + 8 + caption.size() * captionLine + 16;
        BufferedImage image = new BufferedImage(WIDTH, bodyTop + bodyHeight + PAD, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = graphics(image, 1);
        try {
            int top = drawLines(graphics, title, titleFont, TEXT, PAD, titleLine);
            drawLines(graphics, caption, captionFont, MUTED, top + 8, captionLine);
            graphics.translate(0, bodyTop);
            body.accept(graphics);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG image writer is unavailable");
        }
    }

    private static List<String> caption(StatisticsResult statistics) {
        List<String> lines = new ArrayList<>();
        lines.add("Показатель: " + statistics.kind().countTitle().toLowerCase(Locale.ROOT)
                + " в группе; всего в выборке: " + statistics.total() + "; шкала начинается с нуля");
        if (statistics.groupBy() == StatisticsGroupBy.PRODUCT) {
            lines.add("Строка с несколькими ИТ-продуктами учтена в каждом из них, поэтому сумма столбцов может быть больше итога");
        }
        if (statistics.groupBy().mayBeUnspecified(statistics.kind())) {
            lines.add("«" + ReportColumn.UNSPECIFIED + "» (штриховка) — строки без значения группировки: "
                    + statistics.unknownCount() + "; это не нулевое значение");
        }
        if (statistics.groupBy() == StatisticsGroupBy.MONTH) {
            lines.add(monthCaption(statistics.kind()));
        }
        lines.addAll(statistics.notes());
        return lines;
    }

    private static String monthCaption(ReportKind kind) {
        return (switch (kind) {
            case EVENTS -> "Месяц события";
            case PORTFOLIO, SNAPSHOT -> "Месяц создания взаимодействия";
            case DEMAND -> "Месяц подачи заявки";
            case DURATION -> throw new IllegalArgumentException("Duration report has no chart");
            case AGREEMENTS -> throw new IllegalArgumentException("Agreement report has no chart");
        }) + " по московскому времени; месяцы периода без строк показаны нулём";
    }

    private static Chart chart(StatisticsResult statistics) {
        List<Bar> bars = new ArrayList<>();
        for (StatisticsResult.Item item : statistics.items()) {
            bars.add(new Bar(item.label(), item.count(), false));
        }
        if (statistics.groupBy().mayBeUnspecified(statistics.kind())) {
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

    private static LineChart lineChart(StatisticsResult statistics) {
        if (statistics.items().size() > StatisticsResult.MAX_CHART_BARS) {
            throw ReportException.chartLimit(StatisticsResult.MAX_CHART_BARS);
        }
        if (statistics.series().size() > StatisticsResult.MAX_CHART_SERIES) {
            throw ReportException.chartSeriesLimit(StatisticsResult.MAX_CHART_SERIES);
        }
        List<Line> lines = new ArrayList<>();
        if (statistics.seriesBy() == null) {
            lines.add(new Line(statistics.kind().countTitle(),
                    statistics.items().stream().map(StatisticsResult.Item::count).toList(), SERIES_COLORS[0], false));
        } else {
            int color = 0;
            for (StatisticsResult.Series series : statistics.series()) {
                lines.add(new Line(series.label(), series.counts(),
                        series.unspecified() ? MUTED : SERIES_COLORS[color++ % SERIES_COLORS.length], series.unspecified()));
            }
        }
        long max = lines.stream().flatMap(line -> line.values().stream()).mapToLong(Long::longValue).max().orElse(0);
        long step = step(max);
        boolean pointLabels = statistics.items().size() * lines.size() <= MAX_POINT_LABELS;
        List<String> caption = new ArrayList<>();
        caption.add("Показатель: " + statistics.kind().countTitle().toLowerCase(Locale.ROOT) + " за месяц; всего в выборке: "
                + statistics.total() + "; шкала начинается с нуля");
        caption.add(monthCaption(statistics.kind()));
        if (statistics.seriesBy() != null) {
            caption.add("Линии " + statistics.seriesBy().title() + "; «" + ReportColumn.UNSPECIFIED
                    + "» (серая пунктирная линия) — строки без значения, это не ноль");
            if (statistics.seriesBy() == StatisticsGroupBy.PRODUCT) {
                caption.add("Строка с несколькими ИТ-продуктами учтена в линии каждого из них, поэтому сумма линий может быть"
                        + " больше итога");
            }
        }
        if (!pointLabels) {
            caption.add("Точек слишком много для подписей: значения — в таблице основания");
        }
        caption.addAll(statistics.notes());
        return new LineChart(
                statistics.kind().title() + " — график " + statistics.groupBy().title()
                        + (statistics.seriesBy() == null ? "" : ", линии " + statistics.seriesBy().title()),
                caption,
                statistics.kind().countTitle() + ", шкала от нуля",
                statistics.items().stream().map(item -> AXIS_MONTH.format(YearMonth.parse(item.key()))).toList(),
                lines,
                statistics.seriesBy() != null,
                pointLabels,
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

    private static int lineBodyHeight(LineChart chart) {
        return (chart.legend() ? chart.lines().size() * LEGEND_ROW : 0) + PLOT_TITLE_HEIGHT + PLOT_HEIGHT + MONTH_LABELS_HEIGHT;
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

    private static BufferedImage scaledImage(int height, Consumer<Graphics2D> body) {
        BufferedImage image = new BufferedImage(WIDTH * PDF_SCALE, height * PDF_SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = graphics(image, PDF_SCALE);
        try {
            body.accept(graphics);
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

    private static void drawBody(Graphics2D graphics, Font font, Chart chart, List<Row> rows) {
        Font textFont = font.deriveFont(TEXT_SIZE);
        graphics.setFont(textFont);
        int gridTop = AXIS_HEIGHT;
        int gridBottom = bodyHeight(rows);
        graphics.setColor(MUTED);
        graphics.drawString(chart.axisTitle(), BAR_LEFT, 18);
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

    private static void drawLineBody(Graphics2D graphics, Font font, LineChart chart) {
        Font textFont = font.deriveFont(TEXT_SIZE);
        Font smallFont = font.deriveFont(CAPTION_SIZE);
        graphics.setFont(textFont);
        int top = 0;
        if (chart.legend()) {
            for (Line line : chart.lines()) {
                int middle = top + LEGEND_ROW / 2;
                graphics.setColor(line.color());
                graphics.setStroke(lineStroke(line));
                graphics.drawLine(PAD, middle, PAD + 32, middle);
                point(graphics, line, PAD + 16, middle);
                graphics.setColor(line.unspecified() ? MUTED : TEXT);
                graphics.drawString(ellipsize(sanitize(line.label(), textFont), textFont, WIDTH - 2 * PAD - 44), PAD + 44, middle + 5);
                top += LEGEND_ROW;
            }
        }
        graphics.setStroke(new BasicStroke(1f));
        graphics.setColor(MUTED);
        graphics.drawString(chart.axisTitle(), PLOT_LEFT, top + 20);
        int plotTop = top + PLOT_TITLE_HEIGHT;
        int plotBottom = plotTop + PLOT_HEIGHT;
        for (long tick = 0; tick <= chart.axisMax(); tick += chart.step()) {
            int y = plotY(tick, chart.axisMax(), plotTop, plotBottom);
            graphics.setColor(tick == 0 ? MUTED : GRID);
            graphics.drawLine(PLOT_LEFT, y, PLOT_RIGHT, y);
            String label = Long.toString(tick);
            graphics.setColor(MUTED);
            graphics.drawString(label, PLOT_LEFT - 10 - width(label, textFont), y + 5);
        }
        int count = chart.months().size();
        if (count == 0) {
            graphics.drawString("Нет строк, удовлетворяющих фильтрам", PLOT_LEFT + 8, plotTop + 26);
            return;
        }
        graphics.setFont(smallFont);
        int every = Math.max(1, (int) Math.ceil(count * (double) MONTH_LABEL_SPACE / (PLOT_RIGHT - PLOT_LEFT)));
        for (int index = 0; index < count; index += every) {
            String label = chart.months().get(index);
            int x = plotX(index, count);
            graphics.setColor(MUTED);
            graphics.drawLine(x, plotBottom, x, plotBottom + 5);
            graphics.drawString(label, x - width(label, smallFont) / 2, plotBottom + 21);
        }
        graphics.drawString("Месяц", PLOT_RIGHT - width("Месяц", smallFont), plotBottom + 39);
        for (Line line : chart.lines()) {
            Path2D path = new Path2D.Double();
            for (int index = 0; index < count; index++) {
                double x = plotX(index, count);
                double y = plotY(line.values().get(index), chart.axisMax(), plotTop, plotBottom);
                if (index == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            graphics.setColor(line.color());
            graphics.setStroke(lineStroke(line));
            graphics.draw(path);
        }
        for (Line line : chart.lines()) {
            for (int index = 0; index < count; index++) {
                long value = line.values().get(index);
                int x = plotX(index, count);
                int y = plotY(value, chart.axisMax(), plotTop, plotBottom);
                point(graphics, line, x, y);
                if (chart.pointLabels()) {
                    String label = Long.toString(value);
                    graphics.setColor(line.unspecified() ? MUTED : TEXT);
                    graphics.drawString(label, x - width(label, smallFont) / 2, y - 9);
                }
            }
        }
    }

    private static BasicStroke lineStroke(Line line) {
        return line.unspecified()
                ? new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{8f, 6f}, 0f)
                : new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    private static void point(Graphics2D graphics, Line line, int x, int y) {
        Ellipse2D dot = new Ellipse2D.Double(x - 4.5, y - 4.5, 9, 9);
        graphics.setColor(line.unspecified() ? Color.WHITE : line.color());
        graphics.fill(dot);
        graphics.setColor(line.color());
        graphics.setStroke(new BasicStroke(2f));
        graphics.draw(dot);
    }

    private static int plotX(int index, int count) {
        return PLOT_LEFT + (int) Math.round((index + 0.5) * (PLOT_RIGHT - PLOT_LEFT) / count);
    }

    private static int plotY(long value, long axisMax, int plotTop, int plotBottom) {
        return plotBottom - (int) Math.round((double) value * (plotBottom - plotTop) / axisMax);
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

    private record Line(String label, List<Long> values, Color color, boolean unspecified) {
    }

    private record LineChart(
            String title,
            List<String> caption,
            String axisTitle,
            List<String> months,
            List<Line> lines,
            boolean legend,
            boolean pointLabels,
            long axisMax,
            long step
    ) {
    }
}
