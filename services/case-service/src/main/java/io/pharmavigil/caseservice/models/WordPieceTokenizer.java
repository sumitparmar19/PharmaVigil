// BERT WordPiece tokenizer in plain Java, so the MedDRA coder runs without Python. It follows the Hugging
// Face BERT pipeline the model was trained with: clean text, space out CJK characters, strip accents and
// lowercase, split on whitespace and punctuation, then greedy longest-match WordPiece with "##" pieces.
// Parity with the Python tokenizer is tested on the bundle's parity vectors.
package io.pharmavigil.caseservice.models;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WordPieceTokenizer {

    static final int MAX_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocab;
    private final boolean lowercase;
    private final boolean addSpecialTokens;
    private final int unkId;
    private final int clsId;
    private final int sepId;

    public WordPieceTokenizer(List<String> vocabulary, boolean lowercase, boolean addSpecialTokens) {
        this.vocab = new HashMap<>();
        for (int i = 0; i < vocabulary.size(); i++) {
            vocab.putIfAbsent(vocabulary.get(i), i);
        }
        this.lowercase = lowercase;
        this.addSpecialTokens = addSpecialTokens;
        this.unkId = id("[UNK]");
        this.clsId = vocab.getOrDefault("[CLS]", -1);
        this.sepId = vocab.getOrDefault("[SEP]", -1);
    }

    private int id(String token) {
        Integer i = vocab.get(token);
        if (i == null) {
            throw new IllegalArgumentException("vocabulary has no " + token);
        }
        return i;
    }

    /** Token ids for one text, truncated so that the result (with [CLS]/[SEP] if used) fits maxLength. */
    public long[] encode(String text, int maxLength) {
        List<Integer> ids = new ArrayList<>();
        for (String word : preTokenize(normalize(text))) {
            wordPiece(word, ids);
        }
        int room = addSpecialTokens ? maxLength - 2 : maxLength;
        List<Integer> kept = ids.subList(0, Math.min(ids.size(), Math.max(room, 0)));
        long[] out = new long[kept.size() + (addSpecialTokens ? 2 : 0)];
        int p = 0;
        if (addSpecialTokens) {
            out[p++] = clsId;
        }
        for (int i : kept) {
            out[p++] = i;
        }
        if (addSpecialTokens) {
            out[p] = sepId;
        }
        return out;
    }

    String normalize(String text) {
        StringBuilder clean = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) {
                return;
            }
            if (isWhitespace(cp)) {
                clean.append(' ');
            } else if (isCjk(cp)) {
                clean.append(' ').appendCodePoint(cp).append(' ');
            } else {
                clean.appendCodePoint(cp);
            }
        });
        String s = clean.toString();
        if (lowercase) {
            // Accents are stripped whenever the model is uncased (Hugging Face default).
            s = Normalizer.normalize(s, Normalizer.Form.NFD);
            StringBuilder noMarks = new StringBuilder(s.length());
            s.codePoints().filter(cp -> Character.getType(cp) != Character.NON_SPACING_MARK)
                    .forEach(noMarks::appendCodePoint);
            s = noMarks.toString().toLowerCase(Locale.ROOT);
        }
        return s;
    }

    static List<String> preTokenize(String s) {
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (isWhitespace(cp)) {
                flush(cur, words);
            } else if (isPunctuation(cp)) {
                flush(cur, words);
                words.add(new String(Character.toChars(cp)));
            } else {
                cur.appendCodePoint(cp);
            }
        });
        flush(cur, words);
        return words;
    }

    private static void flush(StringBuilder cur, List<String> words) {
        if (!cur.isEmpty()) {
            words.add(cur.toString());
            cur.setLength(0);
        }
    }

    private void wordPiece(String word, List<Integer> out) {
        if (word.codePointCount(0, word.length()) > MAX_CHARS_PER_WORD) {
            out.add(unkId);
            return;
        }
        List<Integer> pieces = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer found = null;
            while (start < end) {
                String sub = (start > 0 ? "##" : "") + word.substring(start, end);
                found = vocab.get(sub);
                if (found != null) {
                    break;
                }
                end = word.offsetByCodePoints(end, -1);
            }
            if (found == null) {
                out.add(unkId);
                return;
            }
            pieces.add(found);
            start = end;
        }
        out.addAll(pieces);
    }

    static boolean isWhitespace(int cp) {
        return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' || Character.getType(cp) == Character.SPACE_SEPARATOR;
    }

    static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') {
            return false;
        }
        int t = Character.getType(cp);
        return t == Character.CONTROL || t == Character.FORMAT || t == Character.PRIVATE_USE
                || t == Character.SURROGATE || t == Character.UNASSIGNED;
    }

    static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        int t = Character.getType(cp);
        return t == Character.CONNECTOR_PUNCTUATION || t == Character.DASH_PUNCTUATION
                || t == Character.START_PUNCTUATION || t == Character.END_PUNCTUATION
                || t == Character.INITIAL_QUOTE_PUNCTUATION || t == Character.FINAL_QUOTE_PUNCTUATION
                || t == Character.OTHER_PUNCTUATION;
    }

    static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2B73F) || (cp >= 0x2B740 && cp <= 0x2B81F)
                || (cp >= 0x2B820 && cp <= 0x2CEAF) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}
