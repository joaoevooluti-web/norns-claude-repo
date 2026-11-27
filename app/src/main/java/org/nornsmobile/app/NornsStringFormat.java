package org.nornsmobile.app;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaDouble;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * string.format e tostring com a semântica do printf do C usada pelo Lua 5.3 da norns.
 *
 * O LuaJ 3.0.1 ignora largura e precisão em %f/%e/%g (imprime Double.toString),
 * trunca %d para 32 bits, devolve "-1" para %x de negativos e recusa nil, boolean
 * e tabela em %s. Aqui as conversões c d i o u x X e E f F g G a A q s seguem o C:
 * flags "-+ #0", largura e precisão de até dois dígitos e arredondamento exato
 * (metade para par sobre o valor binário) via BigDecimal.
 *
 * Diferenças deliberadas em relação ao C: %d/%i truncam um número fracionário em vez
 * de recusá-lo (comportamento que o LuaJ já tinha), NaN nunca leva sinal, e o %s de
 * um número fracionário usa %.14g como o tostring do Lua. O operador .. continua
 * dentro do LuaJ e não passa por aqui.
 */
final class NornsStringFormat extends VarArgFunction {
    private static final String FLAGS = "-+ #0";
    private static final long SIGN_BIT = 0x8000000000000000L;

    /** Troca string.format e tostring nas globais já criadas por JsePlatform. */
    static void install(Globals globals) {
        LuaValue string = globals.get("string");
        if (string.istable()) string.set("format", new NornsStringFormat());
        globals.set("tostring", new NornsToString());
    }

    /** tostring com %.14g para números fracionários (o LuaJ usa float de 32 bits). */
    static final class NornsToString extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            return toLuaString(arg);
        }
    }

    /** Texto de um número fracionário como o tostring do Lua 5.3 (%.14g). */
    static String doubleToString(double value) {
        if (value != value) return "nan";
        if (value == Double.POSITIVE_INFINITY) return "inf";
        if (value == Double.NEGATIVE_INFINITY) return "-inf";
        boolean negative = value < 0 || (value == 0 && (Double.doubleToRawLongBits(value) & SIGN_BIT) != 0);
        String body = general(Math.abs(value), 14, false, false);
        return negative ? "-" + body : body;
    }

    /** Equivalente ao tostring: respeita __tostring e escreve doubles com 14 dígitos. */
    static LuaString toLuaString(LuaValue value) {
        LuaValue handler = value.metatag(LuaValue.TOSTRING);
        if (!handler.isnil()) {
            LuaValue result = handler.call(value);
            if (!result.isstring()) throw new LuaError("'__tostring' must return a string");
            return result.strvalue();
        }
        if (value instanceof LuaDouble) return LuaString.valueOf(doubleToString(value.todouble()));
        LuaValue text = value.tostring();
        if (!text.isnil()) return text.strvalue();
        return LuaString.valueOf(value.tojstring());
    }

    // ------------------------------------------------------------------ string.format

    @Override
    public Varargs invoke(Varargs args) {
        LuaValue first = args.arg(1);
        if (!first.isstring()) throw argError(1, "string expected, got " + typeName(args, 1));
        LuaString format = first.strvalue();
        byte[] bytes = format.m_bytes;
        int offset = format.m_offset;
        int length = format.m_length;
        Out out = new Out(length + 16);
        int arg = 1;
        int i = 0;
        while (i < length) {
            int c = bytes[offset + i++] & 0xFF;
            if (c != '%') {
                out.put(c);
                continue;
            }
            if (i < length && (bytes[offset + i] & 0xFF) == '%') {
                out.put('%');
                i++;
                continue;
            }
            arg++;
            if (arg > args.narg()) throw argError(arg, "no value");
            Spec spec = new Spec();
            i = spec.parse(bytes, offset, length, i);
            switch (spec.conversion) {
                case 'c':
                    formatChar(out, spec, checkInteger(args, arg));
                    break;
                case 'd': case 'i': case 'o': case 'u': case 'x': case 'X':
                    formatInteger(out, spec, checkInteger(args, arg));
                    break;
                case 'a': case 'A': case 'e': case 'E': case 'f': case 'F': case 'g': case 'G':
                    formatFloat(out, spec, checkNumber(args, arg).todouble());
                    break;
                case 'q':
                    formatQuoted(out, checkString(args, arg));
                    break;
                case 's':
                    formatString(out, spec, toLuaString(args.arg(arg)), arg);
                    break;
                default:
                    throw new LuaError("invalid option '%" + (spec.conversion == 0 ? "" : String.valueOf((char) spec.conversion))
                            + "' to 'format'");
            }
        }
        return out.result();
    }

    private static LuaError argError(int index, String message) {
        return new LuaError("bad argument #" + index + " to 'format' (" + message + ")");
    }

    private static String typeName(Varargs args, int index) {
        return index > args.narg() ? "no value" : args.arg(index).typename();
    }

    private static LuaValue checkNumber(Varargs args, int index) {
        LuaValue number = args.arg(index).tonumber();
        if (number.isnil()) throw argError(index, "number expected, got " + typeName(args, index));
        return number;
    }

    private static LuaString checkString(Varargs args, int index) {
        LuaValue value = args.arg(index);
        if (!value.isstring()) throw argError(index, "string expected, got " + typeName(args, index));
        return value.strvalue();
    }

    /** Inteiro de 64 bits; um valor fracionário é truncado (como já fazia o LuaJ). */
    private static long checkInteger(Varargs args, int index) {
        LuaValue number = checkNumber(args, index);
        if (number.isint()) return number.toint();
        double value = number.todouble();
        if (value != value || value >= 0x1p63 || value < -0x1p63) {
            throw argError(index, "number has no integer representation");
        }
        return (long) value;
    }

    // ------------------------------------------------------------------ especificação

    private static final class Spec {
        boolean left, plus, space, alt, zero;
        int width;
        int precision = -1;
        int conversion;
        boolean modifiers;

        /** Lê flags, largura e precisão a partir de start; devolve o índice após a conversão. */
        int parse(byte[] bytes, int offset, int length, int start) {
            int p = start;
            while (p < length && FLAGS.indexOf(bytes[offset + p] & 0xFF) >= 0) {
                switch (bytes[offset + p]) {
                    case '-': left = true; break;
                    case '+': plus = true; break;
                    case ' ': space = true; break;
                    case '#': alt = true; break;
                    default: zero = true; break;
                }
                p++;
            }
            if (p - start > FLAGS.length()) throw new LuaError("invalid format (repeated flags)");
            if (p < length && isDigit(bytes[offset + p])) {
                width = bytes[offset + p++] - '0';
                if (p < length && isDigit(bytes[offset + p])) width = width * 10 + (bytes[offset + p++] - '0');
            }
            if (p < length && bytes[offset + p] == '.') {
                p++;
                precision = 0;
                if (p < length && isDigit(bytes[offset + p])) {
                    precision = bytes[offset + p++] - '0';
                    if (p < length && isDigit(bytes[offset + p])) precision = precision * 10 + (bytes[offset + p++] - '0');
                }
            }
            if (p < length && isDigit(bytes[offset + p])) throw new LuaError("invalid format (width or precision too long)");
            modifiers = p > start;
            conversion = p < length ? bytes[offset + p] & 0xFF : 0;
            return p < length ? p + 1 : p;
        }

        private static boolean isDigit(byte b) {
            return b >= '0' && b <= '9';
        }
    }

    // ------------------------------------------------------------------ saída

    private static final class Out {
        private byte[] data;
        private int size;

        Out(int capacity) {
            data = new byte[Math.max(capacity, 16)];
        }

        private void ensure(int extra) {
            if (size + extra > data.length) {
                byte[] bigger = new byte[Math.max(data.length * 2, size + extra)];
                System.arraycopy(data, 0, bigger, 0, size);
                data = bigger;
            }
        }

        void put(int b) {
            ensure(1);
            data[size++] = (byte) b;
        }

        void put(byte[] source, int offset, int count) {
            ensure(count);
            System.arraycopy(source, offset, data, size, count);
            size += count;
        }

        /** Texto ASCII (dígitos, sinais, expoentes). */
        void put(String ascii) {
            ensure(ascii.length());
            for (int i = 0; i < ascii.length(); i++) data[size++] = (byte) ascii.charAt(i);
        }

        void fill(int count, int b) {
            if (count <= 0) return;
            ensure(count);
            for (int i = 0; i < count; i++) data[size++] = (byte) b;
        }

        LuaString result() {
            return LuaString.valueOf(data, 0, size);
        }
    }

    /** Sinal + prefixo + corpo com largura; "0" preenche entre o prefixo e o corpo. */
    private static void emit(Out out, Spec spec, String sign, String prefix, String body, boolean zeroPadding) {
        int length = sign.length() + prefix.length() + body.length();
        int fill = spec.width > length ? spec.width - length : 0;
        if (spec.left) {
            out.put(sign);
            out.put(prefix);
            out.put(body);
            out.fill(fill, ' ');
        } else if (spec.zero && zeroPadding) {
            out.put(sign);
            out.put(prefix);
            out.fill(fill, '0');
            out.put(body);
        } else {
            out.fill(fill, ' ');
            out.put(sign);
            out.put(prefix);
            out.put(body);
        }
    }

    // ------------------------------------------------------------------ conversões

    private static void formatChar(Out out, Spec spec, long value) {
        int fill = spec.width > 1 ? spec.width - 1 : 0;
        if (!spec.left) out.fill(fill, ' ');
        out.put((int) value);
        if (spec.left) out.fill(fill, ' ');
    }

    private static void formatString(Out out, Spec spec, LuaString text, int argIndex) {
        if (!spec.modifiers) {
            out.put(text.m_bytes, text.m_offset, text.m_length);
            return;
        }
        for (int i = 0; i < text.m_length; i++) {
            if (text.m_bytes[text.m_offset + i] == 0) throw argError(argIndex, "string contains zeros");
        }
        if (spec.precision < 0 && text.m_length >= 100) {
            out.put(text.m_bytes, text.m_offset, text.m_length);
            return;
        }
        int count = spec.precision >= 0 ? Math.min(spec.precision, text.m_length) : text.m_length;
        int fill = spec.width > count ? spec.width - count : 0;
        if (!spec.left) out.fill(fill, ' ');
        out.put(text.m_bytes, text.m_offset, count);
        if (spec.left) out.fill(fill, ' ');
    }

    /** %q: aspas, \\, \" e quebra de linha com barra, controles em decimal. */
    private static void formatQuoted(Out out, LuaString text) {
        out.put('"');
        for (int i = 0; i < text.m_length; i++) {
            int c = text.m_bytes[text.m_offset + i] & 0xFF;
            if (c == '"' || c == '\\' || c == '\n') {
                out.put('\\');
                out.put(c);
            } else if (c <= 0x1F || c == 0x7F) {
                boolean digitFollows = i + 1 < text.m_length
                        && text.m_bytes[text.m_offset + i + 1] >= '0' && text.m_bytes[text.m_offset + i + 1] <= '9';
                out.put('\\');
                String decimal = Integer.toString(c);
                if (digitFollows) while (decimal.length() < 3) decimal = "0" + decimal;
                out.put(decimal);
            } else {
                out.put(c);
            }
        }
        out.put('"');
    }

    private static void formatInteger(Out out, Spec spec, long value) {
        boolean signed = spec.conversion == 'd' || spec.conversion == 'i';
        boolean negative = signed && value < 0;
        String digits;
        switch (spec.conversion) {
            case 'd': case 'i':
                digits = value == Long.MIN_VALUE ? "9223372036854775808" : Long.toString(Math.abs(value));
                break;
            case 'u': digits = Long.toUnsignedString(value); break;
            case 'o': digits = Long.toOctalString(value); break;
            case 'x': digits = Long.toHexString(value); break;
            default: digits = Long.toHexString(value).toUpperCase(Locale.ROOT); break;
        }
        if (spec.precision == 0 && value == 0) digits = "";
        if (spec.precision > digits.length()) digits = zeros(spec.precision - digits.length()) + digits;
        String prefix = "";
        if (spec.alt) {
            if (spec.conversion == 'x' && value != 0) prefix = "0x";
            else if (spec.conversion == 'X' && value != 0) prefix = "0X";
            else if (spec.conversion == 'o' && (digits.isEmpty() || digits.charAt(0) != '0')) digits = "0" + digits;
        }
        String sign = !signed ? "" : negative ? "-" : spec.plus ? "+" : spec.space ? " " : "";
        emit(out, spec, sign, prefix, digits, spec.precision < 0);
    }

    private static void formatFloat(Out out, Spec spec, double value) {
        int conversion = spec.conversion;
        boolean upper = conversion == 'E' || conversion == 'F' || conversion == 'G' || conversion == 'A';
        boolean nan = value != value;
        boolean negative = !nan && (Double.doubleToRawLongBits(value) & SIGN_BIT) != 0;
        String sign = negative ? "-" : spec.plus ? "+" : spec.space ? " " : "";
        if (nan || Double.isInfinite(value)) {
            String text = nan ? "nan" : "inf";
            emit(out, spec, sign, "", upper ? text.toUpperCase(Locale.ROOT) : text, false);
            return;
        }
        double abs = Math.abs(value);
        String prefix = "";
        String body;
        switch (conversion) {
            case 'f': case 'F':
                body = fixed(abs, spec.precision < 0 ? 6 : spec.precision, spec.alt);
                break;
            case 'e': case 'E':
                body = scientific(abs, spec.precision < 0 ? 6 : spec.precision, spec.alt, upper);
                break;
            case 'g': case 'G':
                body = general(abs, spec.precision < 0 ? 6 : Math.max(spec.precision, 1), spec.alt, upper);
                break;
            default:
                prefix = upper ? "0X" : "0x";
                body = hexFloat(abs, spec.precision, spec.alt, upper);
                break;
        }
        emit(out, spec, sign, prefix, body, true);
    }

    private static String zeros(int count) {
        StringBuilder text = new StringBuilder(Math.max(count, 0));
        for (int i = 0; i < count; i++) text.append('0');
        return text.toString();
    }

    /** %f: valor binário exato arredondado para "precision" casas (metade para par). */
    private static String fixed(double abs, int precision, boolean alt) {
        String text = new BigDecimal(abs).setScale(precision, RoundingMode.HALF_EVEN).toPlainString();
        return alt && precision == 0 ? text + "." : text;
    }

    /** %e: d.ddde+XX com pelo menos dois dígitos no expoente. */
    private static String scientific(double abs, int precision, boolean alt, boolean upper) {
        String digits;
        int exponent;
        if (abs == 0.0) {
            digits = zeros(precision + 1);
            exponent = 0;
        } else {
            BigDecimal rounded = new BigDecimal(abs).round(new MathContext(precision + 1, RoundingMode.HALF_EVEN));
            String unscaled = rounded.unscaledValue().toString();
            exponent = unscaled.length() - rounded.scale() - 1;
            digits = unscaled.length() < precision + 1 ? unscaled + zeros(precision + 1 - unscaled.length()) : unscaled;
        }
        StringBuilder text = new StringBuilder();
        text.append(digits.charAt(0));
        if (precision > 0 || alt) text.append('.');
        text.append(digits, 1, digits.length());
        text.append(upper ? 'E' : 'e');
        text.append(exponent < 0 ? '-' : '+');
        int magnitude = Math.abs(exponent);
        if (magnitude < 10) text.append('0');
        text.append(magnitude);
        return text.toString();
    }

    /** %g: P algarismos significativos, estilo e ou f conforme o expoente, sem zeros finais. */
    private static String general(double abs, int significant, boolean alt, boolean upper) {
        int exponent = 0;
        if (abs != 0.0) {
            BigDecimal rounded = new BigDecimal(abs).round(new MathContext(significant, RoundingMode.HALF_EVEN));
            exponent = rounded.precision() - rounded.scale() - 1;
        }
        if (exponent < -4 || exponent >= significant) {
            String text = scientific(abs, significant - 1, alt, upper);
            if (alt) return text;
            int e = text.indexOf(upper ? 'E' : 'e');
            return stripZeros(text.substring(0, e)) + text.substring(e);
        }
        String text = fixed(abs, significant - 1 - exponent, alt);
        return alt ? text : stripZeros(text);
    }

    private static String stripZeros(String text) {
        if (text.indexOf('.') < 0) return text;
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') end--;
        if (end > 0 && text.charAt(end - 1) == '.') end--;
        return text.substring(0, end);
    }

    /** %a (sem o prefixo 0x): mantissa hexadecimal e expoente binário como o glibc. */
    private static String hexFloat(double abs, int precision, boolean alt, boolean upper) {
        long bits = Double.doubleToRawLongBits(abs);
        int biased = (int) ((bits >>> 52) & 0x7FF);
        long mantissa = bits & 0xFFFFFFFFFFFFFL;
        int lead = biased == 0 ? 0 : 1;
        int exponent = biased == 0 ? (mantissa == 0 ? 0 : -1022) : biased - 1023;
        String fraction;
        if (precision < 0) {
            fraction = stripTrailingZeros(hex13(mantissa));
        } else if (precision >= 13) {
            fraction = hex13(mantissa) + zeros(precision - 13);
        } else {
            int drop = 4 * (13 - precision);
            long kept = mantissa >>> drop;
            long rest = mantissa & ((1L << drop) - 1);
            long half = 1L << (drop - 1);
            boolean odd = precision == 0 ? (lead & 1) != 0 : (kept & 1) != 0;
            if (rest > half || (rest == half && odd)) kept++;
            if (precision == 0) {
                if (kept != 0) lead++;
                fraction = "";
            } else {
                if ((kept >>> (4 * precision)) != 0) {
                    lead++;
                    kept &= (1L << (4 * precision)) - 1;
                }
                String hex = Long.toHexString(kept);
                fraction = zeros(precision - hex.length()) + hex;
            }
        }
        StringBuilder text = new StringBuilder();
        text.append(lead);
        if (!fraction.isEmpty() || alt) text.append('.');
        text.append(fraction);
        text.append('p').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        return upper ? text.toString().toUpperCase(Locale.ROOT) : text.toString();
    }

    private static String hex13(long mantissa) {
        String hex = Long.toHexString(mantissa);
        return zeros(13 - hex.length()) + hex;
    }

    private static String stripTrailingZeros(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') end--;
        return text.substring(0, end);
    }
}
