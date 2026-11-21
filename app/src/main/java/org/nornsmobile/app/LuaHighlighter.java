package org.nornsmobile.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Analisador léxico simples de Lua para realce de sintaxe (Java puro). */
final class LuaHighlighter {

    static final int COMMENT = 1;
    static final int STRING = 2;
    static final int NUMBER = 3;
    static final int KEYWORD = 4;
    static final int API = 5;

    /** Lista de tokens: data[3*i]=início, data[3*i+1]=fim (exclusivo), data[3*i+2]=tipo. */
    static final class Tokens {
        int[] data = new int[768];
        int n = 0;

        void add(int start, int end, int type) {
            if (end <= start) return;
            if (3 * (n + 1) > data.length) data = Arrays.copyOf(data, data.length * 2);
            data[3 * n] = start;
            data[3 * n + 1] = end;
            data[3 * n + 2] = type;
            n++;
        }
    }

    private static final Set<String> KEYWORDS = new HashSet<String>(Arrays.asList(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if",
            "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while"));

    private static final Set<String> APIS = new HashSet<String>(Arrays.asList(
            "screen", "engine", "params", "clock", "metro", "util", "midi", "softcut", "grid", "arc",
            "crow", "osc", "norns", "controlspec", "paramset", "musicutil", "MusicUtil", "tab",
            "init", "redraw", "key", "enc", "cleanup", "require", "include", "print", "_path"));

    private LuaHighlighter() {
    }

    private static boolean isIdStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdPart(char c) {
        return isIdStart(c) || (c >= '0' && c <= '9');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Se em i começa um "[=*[" retorna o número de '=' (>=0); caso contrário -1. */
    private static int longBracketLevel(CharSequence s, int i) {
        int n = s.length();
        if (i >= n || s.charAt(i) != '[') return -1;
        int j = i + 1;
        int level = 0;
        while (j < n && s.charAt(j) == '=') {
            level++;
            j++;
        }
        if (j < n && s.charAt(j) == '[') return level;
        return -1;
    }

    /** Índice logo após o fecho "]=*]" correspondente (ou n se não houver). */
    private static int endOfLong(CharSequence s, int from, int level) {
        int n = s.length();
        int i = from;
        while (i < n) {
            if (s.charAt(i) == ']') {
                int j = i + 1;
                int l = 0;
                while (j < n && s.charAt(j) == '=') {
                    l++;
                    j++;
                }
                if (l == level && j < n && s.charAt(j) == ']') return j + 1;
            }
            i++;
        }
        return n;
    }

    static Tokens scan(CharSequence s) {
        Tokens t = new Tokens();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                int lvl = longBracketLevel(s, i + 2);
                int end;
                if (lvl >= 0) {
                    end = endOfLong(s, i + 2 + lvl + 2, lvl);
                } else {
                    end = i + 2;
                    while (end < n && s.charAt(end) != '\n') end++;
                }
                t.add(i, end, COMMENT);
                i = end;
            } else if (c == '[' && longBracketLevel(s, i) >= 0) {
                int lvl = longBracketLevel(s, i);
                int end = endOfLong(s, i + lvl + 2, lvl);
                t.add(i, end, STRING);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == c) {
                        j++;
                        break;
                    }
                    if (d == '\n') break;
                    j++;
                }
                if (j > n) j = n;
                t.add(i, j, STRING);
                i = j;
            } else if (isDigit(c) || (c == '.' && i + 1 < n && isDigit(s.charAt(i + 1)))) {
                int j = i + 1;
                boolean hex = c == '0' && j < n && (s.charAt(j) == 'x' || s.charAt(j) == 'X');
                while (j < n) {
                    char d = s.charAt(j);
                    if (isIdPart(d) || d == '.') {
                        j++;
                    } else if ((d == '+' || d == '-') && !hex && (s.charAt(j - 1) == 'e' || s.charAt(j - 1) == 'E')) {
                        j++;
                    } else {
                        break;
                    }
                }
                t.add(i, j, NUMBER);
                i = j;
            } else if (isIdStart(c)) {
                int j = i + 1;
                while (j < n && isIdPart(s.charAt(j))) j++;
                String w = s.subSequence(i, j).toString();
                boolean afterDot = i > 0 && s.charAt(i - 1) == '.';
                if (KEYWORDS.contains(w)) t.add(i, j, KEYWORD);
                else if (!afterDot && APIS.contains(w)) t.add(i, j, API);
                i = j;
            } else {
                i++;
            }
        }
        return t;
    }
}
