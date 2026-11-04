package vn.edu.p2p.common.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.p2p.common.protocol.ProtocolCodec;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicMetadataPrivacyTest {
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    @Test
    void publicCatalogAndFolderDtosExposeNamesAndRelativePathsOnly() {
        String peerId = UUID.randomUUID().toString();
        Object metadata = List.of(
                new SearchResultItem(1, "report.pdf", 12, "a".repeat(64), 1),
                new FileSourcesResponse(1, List.of(new FileSourceInfo(peerId,
                        "COMPLETE", "203.0.113.7", 7001, 1, "request-scoped-ticket"))),
                new FolderSearchItem(2, "shared-documents", 1, 12, 1),
                new FolderDetailResponse(2, "shared-documents",
                        List.of(new FolderFileInfo(1, "docs/report.pdf", 12, "a".repeat(64))),
                        List.of("docs")),
                new TransferHistoryResponse(List.of(new TransferHistoryItemDto(
                        UUID.randomUUID().toString(), 1, "report.pdf", 12,
                        "COMPLETED", 12, 1, 2L, null, 1)))
        );
        String json = ProtocolCodec.toPayload(metadata).toString();

        assertFalse(Path.of("docs/report.pdf").isAbsolute());
        assertFalse(json.contains("C:\\Users\\"));
        assertFalse(json.contains("/home/"));
        assertFalse(json.contains("\\\\"));
        assertFalse(json.contains("private-key"));
        assertFalse(json.contains("machine-name"));
        assertFalse(json.contains("192.168."));
    }
}
