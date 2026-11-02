package com.rsshub.adminapi;

import com.rsshub.adminapi.SubscriptionService.ImportResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OPML in and out (FR-2.6). The export is a file download rather than a JSON body because the whole point is to hand
 * it to another reader, and that reader gets to choose whether it wants the group nesting — which it cannot do if the
 * only thing on offer is our own row ids.
 */
@RestController
@RequestMapping("/api/opml")
public class OpmlController {

    /**
     * A filename an operator will recognise three months later, and that no two exports overwrite. The literal parts
     * are quoted: an unquoted {@code .opml} would make {@code o} a pattern letter and the class would fail to load.
     */
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("'subscriptions-'yyyyMMdd'-'HHmm'.opml'").withZone(ZoneOffset.UTC);

    private final SubscriptionService subscriptions;

    public OpmlController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping
    public ResponseEntity<String> export() {
        String xml = Opml.export(subscriptions.subscriptions());
        return ResponseEntity.ok()
            .contentType(new MediaType("application", "opml+xml", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + STAMP.format(Instant.now()) + "\"")
            .body(xml);
    }

    /**
     * The body is the document itself, sent as {@code text/xml} or {@code application/xml}. Plain text is accepted
     * because the file picker in a browser sends whatever the operating system thinks an {@code .opml} file is, and
     * on Windows that is frequently {@code application/octet-stream}.
     */
    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE,
        MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public ImportResult importDocument(@RequestBody String xml) {
        return subscriptions.importOpml(xml);
    }
}
