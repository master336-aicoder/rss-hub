package com.rsshub.feedparse;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 03 §5.2's first step, and the reason it is a step at all: a Chinese-language feed whose header and XML prolog both
 * claim UTF-8 while the bytes are GBK. Trusting the label is how "标题全是乱码" reaches the database, so a label that
 * cannot round-trip is treated as no label.
 */
class DocumentDecoderTest {

    @Test
    void honoursADeclaredCharsetThatActuallyWorks() {
        byte[] body = "<r>标题</r>".getBytes(Charset.forName("GB18030"));
        assertThat(DocumentDecoder.decode(body, "gb18030")).isEqualTo("<r>标题</r>");
    }

    @Test
    void refusesAMislabelledUtf8AndFallsBackToTheRealEncoding() {
        byte[] gbk = "<rss><title>中文标题</title></rss>".getBytes(Charset.forName("GB18030"));
        // The header says UTF-8 and the XML prolog agrees; both are wrong, and the result must still be readable.
        String decoded = DocumentDecoder.decode(withUtf8Prolog(gbk), "application/rss+xml; charset=UTF-8");
        assertThat(decoded).contains("中文标题");
    }

    @Test
    void readsTheCharsetFromThePrologWhenTheHeaderSaysNothing() {
        byte[] body = "<?xml version=\"1.0\" encoding=\"windows-1252\"?><r>café</r>"
            .getBytes(Charset.forName("windows-1252"));
        assertThat(DocumentDecoder.decode(body, "application/rss+xml")).isEqualTo("<?xml version=\"1.0\""
            + " encoding=\"windows-1252\"?><r>café</r>");
    }

    @Test
    void stripsAndHonoursAByteOrderMark() {
        byte[] withBom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
            "<r>ok</r>".getBytes(StandardCharsets.UTF_8));
        assertThat(DocumentDecoder.decode(withBom, null)).isEqualTo("<r>ok</r>");
    }

    @Test
    void fallsBackToUtf8WhenNothingIsDeclaredAndTheBytesAreSane() {
        assertThat(DocumentDecoder.decode("<r>日本語</r>".getBytes(StandardCharsets.UTF_8), null))
            .isEqualTo("<r>日本語</r>");
    }

    @Test
    void neverProducesReplacementCharactersFromAJunkCharsetLabel() {
        byte[] body = "<r>text</r>".getBytes(StandardCharsets.UTF_8);
        String decoded = DocumentDecoder.decode(body, "application/xml; charset=x-sjis-not-a-real-name");
        assertThat(decoded).isEqualTo("<r>text</r>");
        // The replacement character is checked by code point, because a literal one in a source file is invisible.
        assertThat(decoded.codePoints().anyMatch(cp -> cp == 0xFFFD)).isFalse();
    }

    private static byte[] withUtf8Prolog(byte[] gbkBody) {
        byte[] prolog = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>".getBytes(StandardCharsets.US_ASCII);
        return concat(prolog, gbkBody);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
