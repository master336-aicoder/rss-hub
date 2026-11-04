package com.rsshub.adminapi;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns the block of text an operator pastes into one candidate address per subscription, and nothing more: no
 * normalisation, no SSRF check, no database. Splitting is a pure text operation and the decision of whether a token
 * is a valid target belongs to {@link SubscriptionService}, which owns the guard.
 *
 * <p>The separators are the ones people actually type when they paste a list: commas (both widths), semicolons (both
 * widths), the ideographic comma, and whitespace of any kind including newlines — a line break is what a table column
 * or a chat log gives you.
 *
 * <p>The colon is the difficult one, because it was asked for as a separator and it is also load-bearing inside every
 * URL. It splits a token only when it is neither the scheme colon ({@code ://}) nor a port colon ({@code :8443/}), and
 * then its meaning depends on what is on its left: an address on the left makes it a second address, a name on the
 * left makes the remainder the address and the name the title. The same reading applies to a name standing alone in
 * front of an address on the same line, which is what a chat log looks like.
 *
 * <p>A line that yields nothing address-like still produces one candidate. Silently dropping it would make a paste of
 * twenty lines that lands eighteen indistinguishable from a paste of eighteen, and the plan table has to be able to
 * point at the line the splitter could not read.
 */
final class PastedSubscriptions {

    /** One pasted candidate: where it came from, the name carried in front of it, and the bare address text. */
    record Candidate(int lineNo, String label, String address) {
    }

    private static final Pattern TOKENS = Pattern.compile("[^\\s,，;；、]+");

    private PastedSubscriptions() {
    }

    static List<Candidate> split(String text) {
        List<Candidate> items = new ArrayList<>();
        if (text == null) {
            return items;
        }
        String[] lines = text.split("\\R");
        for (int line = 0; line < lines.length; line++) {
            List<Candidate> pieces = new ArrayList<>();
            var matcher = TOKENS.matcher(lines[line]);
            while (matcher.find()) {
                emit(pieces, line + 1, matcher.group());
            }
            nameTheAddresses(items, pieces);
        }
        return items;
    }

    /** Consumes one token, which may hold several addresses (colon-separated) or a name plus one address. */
    private static void emit(List<Candidate> items, int lineNo, String token) {
        String rest = token;
        while (!rest.isEmpty()) {
            int colon = firstSplitColon(rest);
            if (colon < 0) {
                items.add(new Candidate(lineNo, null, rest));
                return;
            }
            String left = rest.substring(0, colon).trim();
            String right = rest.substring(colon + 1).trim();
            if (!looksLikeAddress(left) && looksLikeAddress(right)) {
                items.add(new Candidate(lineNo, left, right));
                return;
            }
            items.add(new Candidate(lineNo, null, left));
            rest = right;
        }
    }

    /**
     * A piece that is not address-like is the name of the next address on the same line — that is how
     * {@code 阮一峰 https://…} and {@code 阮一峰:https://…} end up saying the same thing. Names left over at the end of a
     * line, with no address to name, go back into the list as candidates so they show up as their own row.
     */
    private static void nameTheAddresses(List<Candidate> items, List<Candidate> pieces) {
        String pending = null;
        for (Candidate piece : pieces) {
            if (piece.label() == null && !looksLikeAddress(piece.address())) {
                pending = pending == null ? piece.address() : pending + " " + piece.address();
                continue;
            }
            String label = piece.label();
            if (pending != null) {
                label = label == null ? pending : pending + " " + label;
            }
            items.add(new Candidate(piece.lineNo(), label, piece.address()));
            pending = null;
        }
        if (pending != null) {
            items.add(new Candidate(pieces.get(pieces.size() - 1).lineNo(), null, pending));
        }
    }

    /**
     * Index of the first colon that is allowed to split, or {@code -1}. A colon followed by {@code //} is a scheme and
     * a colon followed by digits and then the end or {@code /} {@code ?} {@code #} is a port; both belong to the
     * address and must survive the split intact.
     */
    private static int firstSplitColon(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != ':' && c != '：') {
                continue;
            }
            String after = token.substring(i + 1);
            if (after.startsWith("//") || after.matches("\\d+([/?#].*)?")) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /**
     * Whether a fragment can be an address at all: a scheme, a protocol-relative start, or something host-shaped —
     * no whitespace, with both a dot and a slash in it. Anything else, a Chinese name above all, is a label, and a
     * label with no address to name comes back as its own candidate at the end of the line.
     */
    private static boolean looksLikeAddress(String value) {
        String lower = value.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")) {
            return true;
        }
        return !value.isBlank() && value.indexOf('.') >= 0 && value.indexOf('/') >= 0 && !containsSpace(value);
    }

    private static boolean containsSpace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }
}
