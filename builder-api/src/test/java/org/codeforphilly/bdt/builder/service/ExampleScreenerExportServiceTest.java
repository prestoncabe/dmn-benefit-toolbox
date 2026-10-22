package org.codeforphilly.bdt.builder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExampleScreenerExportServiceTest {
    @TempDir
    Path exportRoot;

    @Test
    void preservesNextStepsAndClearedFieldsInExampleExports() {
        Map<String, Object> eligible = new LinkedHashMap<>();
        eligible.put("applyUrl", "https://example.org/apply");
        eligible.put("helpPhone", null);
        eligible.put("body", "Instructions\n\n- Bring ID");
        var content = Map.of("eligible", eligible, "ineligible", Map.of("note", "A note"));
        var exported = new ExampleScreenerExportService(null, "demo-bdt-dev.appspot.com").firestoreDocumentForExport(
            Map.of("name", "Benefit", "nextSteps", content), "benefit-1");
        assertEquals(content, exported.get("nextSteps"));
    }

    @Test
    void resetRemovesOldExportDataAndKeepsOtherFiles() throws Exception {
        Files.writeString(exportRoot.resolve("README.md"), "Seed editing guidance");
        Path notes = exportRoot.resolve("notes/scenarios.md");
        Files.createDirectories(notes.getParent());
        Files.writeString(notes, "Preview scenarios");
        Files.writeString(exportRoot.resolve("manifest.json"), "old manifest");
        Path oldData = exportRoot.resolve("firestore/workingScreener/old.json");
        Files.createDirectories(oldData.getParent());
        Files.writeString(oldData, "old screener");
        Path oldDmn = exportRoot.resolve("storage/check/old.dmn");
        Files.createDirectories(oldDmn.getParent());
        Files.writeString(oldDmn, "old DMN");

        ExampleScreenerExportService.resetExportRoot(exportRoot);

        assertTrue(Files.isDirectory(exportRoot));
        assertEquals("Seed editing guidance", Files.readString(exportRoot.resolve("README.md")));
        assertEquals("Preview scenarios", Files.readString(notes));
        assertFalse(Files.exists(exportRoot.resolve("manifest.json")));
        assertFalse(Files.exists(exportRoot.resolve("firestore")));
        assertFalse(Files.exists(exportRoot.resolve("storage")));
    }
}
