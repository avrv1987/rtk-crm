package ru.rtk.crm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

// Тесты базы идут на H2, а он понимает комментарии «//» внутри SQL; PostgreSQL на них падает (так сломалась
// правка договора в 2.1). Комментарии в тексте запросов допускаются только в виде «--».
class SqlTextBlockCommentTest {

    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern JAVA_COMMENT = Pattern.compile("^\\s*(//|/\\*)", Pattern.MULTILINE);

    @Test
    void sqlTextBlocksHaveNoJavaComments() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher block = TEXT_BLOCK.matcher(Files.readString(file));
                while (block.find()) {
                    if (JAVA_COMMENT.matcher(block.group(1)).find()) {
                        found.add(file.toString());
                    }
                }
            }
        }
        assertThat(found).isEmpty();
    }
}
