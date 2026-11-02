package com.rsshub.feedparse;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bytes to text, the way 03 §5.2 asks for it: BOM first, then the charset the publisher actually declared, and
 * only then a small ladder of guesses. The failure this exists for is the Chinese-web case the design calls out
 * ({@code rss-guide.md:611}): a document full of GBK bytes whose header and XML prolog both claim UTF-8. Decoding
 * that as UTF-8 produces replacement characters, and every entry after it is silently mangled — so the decoder
 * rejects a claimed charset the moment it cannot round-trip, instead of trusting it.
 *
 * <p>Nothing here sniffs the format; that is {@link FeedParser}'s job, and it runs on already-decoded text.
 */
public final class DocumentDecoder {

    /**
     * The prolog is not always the first bytes: whitespace, a BOM or a stray processing instruction may precede it,
     * and some publishers start the file with a comment. So the pattern searches instead of anchoring at 0.
     */
    private static final Pattern PROLOG_ENCODING = Pattern.compile(
        "<\\?xml[^>]*?\\bencoding\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Last-resort guesses, ordered. windows-1252 and GB18030 are the two that a mislabelled feed turns out to be
     * in practice; ISO-8859-1 is last because it decodes literally anything, so choosing it early would hide a
     * real problem behind plausible-looking noise.
     */
    private static final List<String> GUESSES = List.of("UTF-8", "GB18030", "windows-1252", "ISO-8859-1");

    private DocumentDecoder() {
    }

    /**
     * @param declaredCharset the HTTP {@code Content-Type} charset, or null. May also be handed the value from an
     *        {@code <?xml encoding=...?>} prolog when there is no HTTP header.
     */
    public static String decode(byte[] body, String declaredCharset) {
        Bom bom = Bom.of(body);
        if (bom != null) {
            return new String(body, bom.offset(), body.length - bom.offset(), bom.charset());
        }
        String fromProlog = prologCharset(body);
        String claimed = firstUsable(declaredCharset, fromProlog);
        if (claimed != null) {
            String decoded = tryStrict(body, claimed);
            if (decoded != null) {
                return decoded;
            }
            // The publisher lied about the encoding. Fall through to the ladder rather than trusting the label:
            // a wrong-but-decodable charset is exactly how "标题全是乱码" gets into a database.
        }
        for (String guess : GUESSES) {
            String decoded = tryStrict(body, guess);
            if (decoded != null) {
                return decoded;
            }
        }
        // ISO-8859-1 above cannot fail, so reaching here means one of the names above is unavailable in this JVM.
        return new String(body, StandardCharsets.ISO_8859_1);
    }

    /** The charset an XML prolog claims, or null. Read as ISO-8859-1 because every candidate is ASCII-safe there. */
    private static String prologCharset(byte[] body) {
        int window = Math.min(body.length, 512);
        String head = new String(body, 0, window, StandardCharsets.ISO_8859_1);
        Matcher matcher = PROLOG_ENCODING.matcher(head);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String firstUsable(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && isSupported(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static boolean isSupported(String name) {
        try {
            return Charset.isSupported(name);
        } catch (IllegalArgumentException e) {
            // Some feeds advertise "utf8" or "x-sjis"; Charset.isSupported refuses them and the ladder handles it.
            // UnsupportedCharsetException is an IllegalArgumentException, so one catch covers both.
            return false;
        }
    }

    /**
     * Strict decode: report on any unmappable or malformed sequence, and refuse a result that contains the
     * replacement character. A tolerant decoder would hand back {@code "} runs and the parser would store them.
     */
    private static String tryStrict(byte[] body, String charsetName) {
        Charset charset;
        try {
            charset = Charset.forName(charsetName);
        } catch (IllegalArgumentException e) {
            return null;
        }
        CharsetDecoder decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String decoded = decoder.decode(ByteBuffer.wrap(body)).toString();
            return decoded.indexOf('\uFFFD') >= 0 ? null : decoded;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Byte-order marks we are willing to honour; anything else is not a BOM. */
    private record Bom(Charset charset, int offset) {
        private static final byte[] UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        private static final byte[] UTF16LE = {(byte) 0xFF, (byte) 0xFE};
        private static final byte[] UTF16BE = {(byte) 0xFE, (byte) 0xFF};

        static Bom of(byte[] body) {
            if (startsWith(body, UTF8)) {
                return new Bom(StandardCharsets.UTF_8, UTF8.length);
            }
            // A UTF-32 BOM starts with the UTF-16LE one, so the order of these checks is load-bearing.
            if (startsWith(body, UTF16LE)) {
                return new Bom(StandardCharsets.UTF_16LE, UTF16LE.length);
            }
            if (startsWith(body, UTF16BE)) {
                return new Bom(StandardCharsets.UTF_16BE, UTF16BE.length);
            }
            return null;
        }

        private static boolean startsWith(byte[] body, byte[] prefix) {
            if (body.length < prefix.length) {
                return false;
            }
            for (int i = 0; i < prefix.length; i++) {
                if (body[i] != prefix[i]) {
                    return false;
                }
            }
            return true;
        }

        String name() {
            return charset().name().toLowerCase(Locale.ROOT);
        }
    }
}
