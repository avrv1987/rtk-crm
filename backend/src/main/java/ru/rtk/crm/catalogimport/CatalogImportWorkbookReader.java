package ru.rtk.crm.catalogimport;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.interaction.InteractionValidationException;

@Component
public class CatalogImportWorkbookReader {
    static final int MAX_ROWS = 5_000;
    static final int MAX_COLUMNS = 50;
    static final int MAX_CELL_LENGTH = 4_000;
    static final int MAX_ZIP_ENTRIES = 200;
    static final long MAX_TOTAL_INFLATED_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_DECOMPRESSED_BYTES = 12L * 1024L * 1024L;

    public CatalogImportWorkbookReader() {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_DECOMPRESSED_BYTES);
        ZipSecureFile.setMaxTextSize(MAX_DECOMPRESSED_BYTES);
    }

    public CatalogImportInspectResponse inspect(MultipartFile file) {
        try {
            checkAggregateZipSize(file);
        } catch (IOException exception) {
            throw new InteractionValidationException("file", "Файл не читается как книга XLS или XLSX");
        }
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<CatalogImportSheet> sheets = new ArrayList<>();
            InteractionValidationException firstSheetError = null;
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                Row headerRow = headerRow(sheet, formatter);
                if (headerRow == null) {
                    continue;
                }
                try {
                    sheets.add(new CatalogImportSheet(sheet.getSheetName(), headers(sheet, headerRow, formatter)));
                } catch (InteractionValidationException exception) {
                    firstSheetError = firstSheetError == null ? exception : firstSheetError;
                }
            }
            if (sheets.isEmpty()) {
                throw firstSheetError != null
                        ? firstSheetError
                        : new InteractionValidationException("file", "В книге нет листа со строкой заголовков");
            }
            return new CatalogImportInspectResponse(List.copyOf(sheets));
        } catch (InteractionValidationException exception) {
            throw exception;
        } catch (EncryptedDocumentException exception) {
            throw new InteractionValidationException("file", "Книга защищена паролем; сохраните её без пароля");
        } catch (IOException | RuntimeException exception) {
            throw new InteractionValidationException("file", "Файл не читается как книга XLS или XLSX");
        }
    }

    public CatalogImportWorkbookSheet read(MultipartFile file, String sheetName) {
        try {
            checkAggregateZipSize(file);
        } catch (IOException exception) {
            throw new InteractionValidationException("file", "Файл не читается как книга XLS или XLSX");
        }
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new InteractionValidationException("sheet", "Лист «" + sheetName + "» не найден в книге");
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Row headerRow = headerRow(sheet, formatter);
            if (headerRow == null) {
                throw new InteractionValidationException("sheet", "На листе «" + sheetName + "» нет строки заголовков");
            }
            Map<Integer, String> headersByColumn = headersByColumn(sheet, headerRow, formatter);
            List<CatalogImportWorkbookRow> rows = new ArrayList<>();
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                Map<String, String> errors = new LinkedHashMap<>();
                boolean hasValue = false;
                for (Map.Entry<Integer, String> column : headersByColumn.entrySet()) {
                    String header = column.getValue();
                    Cell cell = row.getCell(column.getKey(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    CellText text = cell == null ? CellText.EMPTY : text(cell, formatter);
                    if (text.error() != null) {
                        errors.put(header, text.error());
                    } else if (text.value().length() > MAX_CELL_LENGTH) {
                        errors.put(header, "Значение длиннее " + MAX_CELL_LENGTH + " символов");
                    }
                    values.put(header, text.value());
                    hasValue = hasValue || !text.value().isEmpty() || text.error() != null;
                }
                if (!hasValue) {
                    continue;
                }
                if (rows.size() == MAX_ROWS) {
                    throw new InteractionValidationException(
                            "file", "Лист «" + sheetName + "»: больше " + MAX_ROWS + " строк с данными; разделите файл"
                    );
                }
                rows.add(new CatalogImportWorkbookRow(rowIndex + 1, Map.copyOf(values), Map.copyOf(errors)));
            }
            return new CatalogImportWorkbookSheet(
                    sheet.getSheetName(), List.copyOf(headersByColumn.values()), List.copyOf(rows)
            );
        } catch (InteractionValidationException exception) {
            throw exception;
        } catch (EncryptedDocumentException exception) {
            throw new InteractionValidationException("file", "Книга защищена паролем; сохраните её без пароля");
        } catch (IOException | RuntimeException exception) {
            throw new InteractionValidationException("file", "Файл не читается как книга XLS или XLSX");
        }
    }

    private void checkAggregateZipSize(MultipartFile file) throws IOException {
        byte[] signature = new byte[4];
        try (InputStream input = file.getInputStream()) {
            if (input.readNBytes(signature, 0, 4) < 4 || signature[0] != 0x50 || signature[1] != 0x4B) {
                return;
            }
        }
        long total = 0;
        int parts = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = file.getInputStream(); ZipInputStream zip = new ZipInputStream(input)) {
            while (zip.getNextEntry() != null) {
                if (++parts > MAX_ZIP_ENTRIES) {
                    throw new InteractionValidationException("file", "В книге больше " + MAX_ZIP_ENTRIES + " частей архива");
                }
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_TOTAL_INFLATED_BYTES) {
                        throw new InteractionValidationException(
                                "file", "Суммарный распакованный объём книги больше "
                                        + MAX_TOTAL_INFLATED_BYTES / (1024 * 1024) + " МиБ"
                        );
                    }
                }
            }
        }
    }

    private Row headerRow(Sheet sheet, DataFormatter formatter) {
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex >= 0 && rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null && lastTextColumn(row, formatter) >= 0) {
                return row;
            }
        }
        return null;
    }

    private List<String> headers(Sheet sheet, Row headerRow, DataFormatter formatter) {
        return List.copyOf(headersByColumn(sheet, headerRow, formatter).values());
    }

    private Map<Integer, String> headersByColumn(Sheet sheet, Row headerRow, DataFormatter formatter) {
        String location = "Лист «" + sheet.getSheetName() + "», строка " + (headerRow.getRowNum() + 1);
        Map<Integer, String> headers = new LinkedHashMap<>();
        Set<String> uniqueHeaders = new LinkedHashSet<>();
        for (int columnIndex = 0; columnIndex <= lastTextColumn(headerRow, formatter); columnIndex++) {
            Cell cell = headerRow.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            CellText text = cell == null ? CellText.EMPTY : text(cell, formatter);
            if (text.error() != null) {
                throw new InteractionValidationException("file", location + ": " + text.error());
            }
            String header = text.value();
            if (header.isEmpty()) {
                continue;
            }
            if (!uniqueHeaders.add(header)) {
                throw new InteractionValidationException("file", location + ": заголовок «" + header + "» повторяется");
            }
            headers.put(columnIndex, header);
        }
        if (headers.size() > MAX_COLUMNS) {
            throw new InteractionValidationException("file", location + ": больше " + MAX_COLUMNS + " столбцов");
        }
        return headers;
    }

    private int lastTextColumn(Row row, DataFormatter formatter) {
        for (int columnIndex = row.getLastCellNum() - 1; columnIndex >= 0; columnIndex--) {
            Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null) {
                CellText text = text(cell, formatter);
                if (!text.value().isEmpty() || text.error() != null) {
                    return columnIndex;
                }
            }
        }
        return -1;
    }

    private CellText text(Cell cell, DataFormatter formatter) {
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            if (cell instanceof XSSFCell xssfCell && !xssfCell.getCTCell().isSetV()) {
                return new CellText("", "Формула без сохранённого значения; откройте и сохраните файл в Excel");
            }
            type = cell.getCachedFormulaResultType();
        }
        return switch (type) {
            case STRING -> new CellText(cell.getStringCellValue().trim(), null);
            case BOOLEAN -> new CellText(cell.getBooleanCellValue() ? "true" : "false", null);
            case NUMERIC -> new CellText(numeric(cell, formatter), null);
            case ERROR -> new CellText("", "Ячейка содержит ошибку Excel");
            default -> new CellText("", null);
        };
    }

    private String numeric(Cell cell, DataFormatter formatter) {
        if (DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return formatter.formatRawCellContents(
                cell.getNumericCellValue(), cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString()
        ).trim();
    }

    private record CellText(String value, String error) {
        private static final CellText EMPTY = new CellText("", null);
    }
}
