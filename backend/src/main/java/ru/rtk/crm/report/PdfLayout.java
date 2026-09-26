package ru.rtk.crm.report;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

final class PdfLayout {
    static final String FONT_RESOURCE = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    static final float TITLE_SIZE = 12f;
    static final float NOTE_SIZE = 8f;

    private static final PDRectangle PAGE_SIZE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGIN = 28f;
    private static final float FOOTER_HEIGHT = 16f;
    private static final float CELL_SIZE = 7.5f;
    private static final float LINE_FACTOR = 1.25f;
    private static final float PADDING = 2.5f;
    private static final int KEEP_TOGETHER_LINES = 12;
    private static final String ELLIPSIS = "…";

    private final PDDocument pdf;
    private final PDType0Font font;
    private final CmapLookup cmap;
    private final String title;
    private final List<String> notes;
    private final OffsetDateTime generatedAt;
    private final float contentWidth = PAGE_SIZE.getWidth() - 2 * MARGIN;
    private final float bottom = MARGIN + FOOTER_HEIGHT;
    private List<String> headers = List.of();
    private float[] widths = new float[0];
    private PDPageContentStream stream;
    private float y;
    private int pageNumber;
    private int rowsOnPage;

    interface Content {
        void render(PdfLayout layout) throws IOException;
    }

    private PdfLayout(PDDocument pdf, PDType0Font font, String title, List<String> notes, OffsetDateTime generatedAt)
            throws IOException {
        this.pdf = pdf;
        this.font = font;
        this.cmap = ((PDCIDFontType2) font.getDescendantFont()).getTrueTypeFont().getUnicodeCmapLookup();
        this.title = title;
        this.notes = notes;
        this.generatedAt = generatedAt;
    }

    static void write(
            String title,
            OffsetDateTime generatedAt,
            List<String> notes,
            OutputStream output,
            Content content
    ) throws IOException {
        try (PDDocument pdf = new PDDocument(IOUtils.createTempFileOnlyStreamCache());
             InputStream fontData = PdfLayout.class.getResourceAsStream(FONT_RESOURCE)) {
            if (fontData == null) {
                throw new IOException("PDF font resource is unavailable");
            }
            PdfLayout layout = new PdfLayout(pdf, PDType0Font.load(pdf, fontData, true), title, notes, generatedAt);
            try {
                layout.newPage();
                layout.paragraph(title, TITLE_SIZE);
                for (String note : notes) {
                    layout.paragraph(note, NOTE_SIZE);
                }
                content.render(layout);
            } finally {
                if (layout.stream != null) {
                    layout.stream.close();
                }
            }
            pdf.getDocumentInformation().setTitle(title);
            pdf.save(output);
        }
    }

    float contentWidth() {
        return contentWidth;
    }

    float available() {
        return y - bottom;
    }

    void gap(float height) {
        y -= height;
    }

    void newPage() throws IOException {
        if (stream != null) {
            stream.close();
        }
        PDPage page = new PDPage(PAGE_SIZE);
        pdf.addPage(page);
        stream = new PDPageContentStream(pdf, page);
        pageNumber++;
        rowsOnPage = 0;
        y = PAGE_SIZE.getHeight() - MARGIN;
        footer();
        if (pageNumber > 1) {
            runningHead();
        }
    }

    void paragraph(String value, float size) throws IOException {
        for (String line : wrap(value, size, contentWidth)) {
            if (y - leading(size) < bottom) {
                newPage();
            }
            line(line, size);
        }
    }

    void image(BufferedImage image, float height) throws IOException {
        stream.drawImage(LosslessFactory.createFromImage(pdf, image), MARGIN, y - height, contentWidth, height);
        y -= height;
    }

    void table(List<String> headers, int[] weights) throws IOException {
        this.headers = headers;
        int totalWeight = 0;
        for (int weight : weights) {
            totalWeight += weight;
        }
        widths = new float[weights.length];
        for (int index = 0; index < weights.length; index++) {
            widths[index] = contentWidth * weights[index] / totalWeight;
        }
        tableHeader();
    }

    void row(List<String> values) throws IOException {
        List<List<String>> cells = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            cells.add(wrap(values.get(index), CELL_SIZE, cellWidth(index)));
        }
        int total = cells.stream().mapToInt(List::size).max().orElse(1);
        int start = 0;
        while (start < total) {
            int fit = (int) Math.floor((y - bottom - 2 * PADDING) / leading(CELL_SIZE));
            int remaining = total - start;
            boolean keepTogether = start == 0 && remaining > fit && remaining <= KEEP_TOGETHER_LINES;
            if (fit < 1 || (keepTogether && rowsOnPage > 0)) {
                newTablePage();
                continue;
            }
            int count = Math.min(fit, remaining);
            drawCells(cells, start, count, false);
            rowsOnPage++;
            start += count;
            if (start < total) {
                newTablePage();
            }
        }
    }

    private void newTablePage() throws IOException {
        newPage();
        tableHeader();
    }

    private void footer() throws IOException {
        String generated = "сформирован " + ReportColumn.DATE_TIME.format(generatedAt.atZoneSameInstant(ReportRequest.ZONE));
        String page = "Стр. " + pageNumber;
        float pageWidth = width(page, NOTE_SIZE);
        text(ellipsize(title + " · " + generated, NOTE_SIZE, contentWidth - pageWidth - 12), MARGIN, MARGIN, NOTE_SIZE);
        text(page, PAGE_SIZE.getWidth() - MARGIN - pageWidth, MARGIN, NOTE_SIZE);
    }

    private void runningHead() throws IOException {
        line(ellipsize(title, NOTE_SIZE, contentWidth), NOTE_SIZE);
        for (String note : notes) {
            line(ellipsize(note, NOTE_SIZE, contentWidth), NOTE_SIZE);
        }
        y -= NOTE_SIZE / 2;
    }

    private void line(String value, float size) throws IOException {
        text(value, MARGIN, y - size, size);
        y -= leading(size);
    }

    private void tableHeader() throws IOException {
        List<List<String>> cells = new ArrayList<>();
        for (int index = 0; index < headers.size(); index++) {
            cells.add(wrap(headers.get(index), CELL_SIZE, cellWidth(index)));
        }
        int lines = cells.stream().mapToInt(List::size).max().orElse(1);
        float height = lines * leading(CELL_SIZE) + 2 * PADDING;
        if (y - height < bottom) {
            newPage();
        }
        drawCells(cells, 0, lines, true);
    }

    private void drawCells(List<List<String>> cells, int start, int count, boolean header) throws IOException {
        float height = count * leading(CELL_SIZE) + 2 * PADDING;
        float x = MARGIN;
        for (int index = 0; index < cells.size(); index++) {
            if (header) {
                stream.setNonStrokingColor(0.9f, 0.9f, 0.9f);
                stream.addRect(x, y - height, widths[index], height);
                stream.fill();
                stream.setNonStrokingColor(0f, 0f, 0f);
            }
            stream.setStrokingColor(0.55f, 0.55f, 0.55f);
            stream.setLineWidth(0.4f);
            stream.addRect(x, y - height, widths[index], height);
            stream.stroke();
            List<String> lines = cells.get(index);
            for (int line = start; line < Math.min(start + count, lines.size()); line++) {
                float baseline = y - PADDING - CELL_SIZE - (line - start) * leading(CELL_SIZE);
                text(lines.get(line), x + PADDING, baseline, CELL_SIZE);
            }
            x += widths[index];
        }
        y -= height;
    }

    private void text(String value, float x, float baseline, float size) throws IOException {
        if (value.isEmpty()) {
            return;
        }
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, baseline);
        stream.showText(value);
        stream.endText();
    }

    private List<String> wrap(String value, float size, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String paragraph : sanitize(value).split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ", -1)) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(candidate, size) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (width(rest, size) > maxWidth) {
                    int cut = fittingPrefix(rest, size, maxWidth);
                    lines.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                line.append(rest);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private int fittingPrefix(String value, float size, float maxWidth) throws IOException {
        int end = Character.charCount(value.codePointAt(0));
        while (end < value.length()) {
            int next = end + Character.charCount(value.codePointAt(end));
            if (width(value.substring(0, next), size) > maxWidth) {
                break;
            }
            end = next;
        }
        return end;
    }

    private String ellipsize(String value, float size, float maxWidth) throws IOException {
        String clean = sanitize(value).replace('\n', ' ');
        if (width(clean, size) <= maxWidth) {
            return clean;
        }
        String prefix = clean.substring(0, fittingPrefix(clean, size, maxWidth - width(ELLIPSIS, size)));
        return prefix + ELLIPSIS;
    }

    private String sanitize(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            if (codePoint == '\n') {
                result.append('\n');
            } else if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                result.append(' ');
            } else if (cmap.getGlyphId(codePoint) == 0) {
                result.append('?');
            } else {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    private float width(String value, float size) throws IOException {
        return font.getStringWidth(value) / 1000f * size;
    }

    private float cellWidth(int index) {
        return widths[index] - 2 * PADDING;
    }

    private float leading(float size) {
        return size * LINE_FACTOR;
    }
}
