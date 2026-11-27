package org.nornsmobile.app;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * string.format e tostring do NornsStringFormat. O LuaJ 3.0.1 ignora largura e precisão em %f/%e/%g, trunca %d para 32 bits
 * e recusa nil/boolean/tabela em %s; aqui tudo segue o printf do C. Os casos de string_format_cases.tsv saíram do printf do
 * glibc (gcc) e cobrem flags, largura, precisão, arredondamento e %a; os demais são regras de erro e de tipo.
 */
public class NornsStringFormatTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() && path.startsWith("app/")) file = new File(path.substring(4));
        assertTrue("Missing file: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
    }

    private static Globals globals() {
        Globals g = JsePlatform.standardGlobals();
        NornsStringFormat.install(g);
        return g;
    }

    private static LuaValue run(Globals g, String source) {
        return g.load(source, "@teste.lua").call();
    }

    /** Executa um trecho Lua que devolve o texto formatado. */
    private static String lua(String expression) {
        return run(globals(), "return " + expression).tojstring();
    }

    private static String format(String format, LuaValue... args) {
        Globals g = globals();
        LuaValue[] all = new LuaValue[args.length + 1];
        all[0] = LuaValue.valueOf(format);
        System.arraycopy(args, 0, all, 1, args.length);
        return g.get("string").get("format").invoke(LuaValue.varargsOf(all)).arg1().tojstring();
    }

    private static String errorOf(String expression) {
        try {
            run(globals(), "return " + expression);
        } catch (LuaError e) {
            return e.getMessage();
        }
        fail("esperava um erro em: " + expression);
        return null;
    }

    private static void assertError(String expected, String expression) {
        String message = errorOf(expression);
        assertTrue("erro \"" + message + "\" não contém \"" + expected + "\" em " + expression, message.contains(expected));
    }

    // ------------------------------------------------------------------ casos do printf do C

    @Test
    public void goldenCasesFromTheCPrintfMatch() throws Exception {
        Globals g = globals();
        LuaValue formatter = g.get("string").get("format");
        String[] lines = read("app/src/test/resources/lua/string_format_cases.tsv").split("\n");
        int checked = 0;
        int[] perKind = new int[3];
        StringBuilder failures = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] columns = line.split("\t", -1);
            assertEquals("linha mal formada: " + line, 4, columns.length);
            LuaValue value;
            switch (columns[0]) {
                case "i":
                    value = LuaValue.valueOf((double) Long.parseLong(columns[2]));
                    perKind[0]++;
                    break;
                case "f":
                    value = columns[2].equals("inf") ? LuaValue.valueOf(Double.POSITIVE_INFINITY)
                            : columns[2].equals("-inf") ? LuaValue.valueOf(Double.NEGATIVE_INFINITY)
                            : columns[2].equals("nan") ? LuaValue.valueOf(Double.NaN)
                            : LuaValue.valueOf(Double.parseDouble(columns[2]));
                    perKind[1]++;
                    break;
                default:
                    value = LuaValue.valueOf(columns[2]);
                    perKind[2]++;
                    break;
            }
            String actual = formatter.call(LuaValue.valueOf(columns[1]), value).tojstring();
            if (!actual.equals(columns[3]) && failures.length() < 2000) {
                failures.append(String.format("%n%s %s -> esperado [%s] obtido [%s]", columns[1], columns[2], columns[3], actual));
            }
            checked++;
        }
        assertEquals("divergências do printf do C:" + failures, 0, failures.length());
        assertTrue("poucos casos: " + checked, checked >= 1400);
        assertTrue("faltam casos inteiros: " + perKind[0], perKind[0] >= 300);
        assertTrue("faltam casos de ponto flutuante: " + perKind[1], perKind[1] >= 700);
        assertTrue("faltam casos de string: " + perKind[2], perKind[2] >= 100);
    }

    // ------------------------------------------------------------------ ponto flutuante

    @Test
    public void floatsHonourWidthPrecisionAndFlags() {
        assertEquals("0.33", lua("string.format('%.2f', 1/3)"));
        assertEquals("  3.1|", lua("string.format('%5.1f|', 3.14159)"));
        assertEquals("3.142   |", lua("string.format('%-8.3f|', 3.14159)"));
        assertEquals("-0003.14", lua("string.format('%08.2f', -3.14159)"));
        assertEquals("+2.5", lua("string.format('%+.1f', 2.5)"));
        assertEquals(" 2.5", lua("string.format('% .1f', 2.5)"));
        assertEquals("1.234568e+04", lua("string.format('%e', 12345.6789)"));
        assertEquals("1.235E+04", lua("string.format('%.3E', 12345.6789)"));
        assertEquals("1e+20", lua("string.format('%g', 1e20)"));
        assertEquals("0.0001", lua("string.format('%g', 0.0001)"));
        assertEquals("1e-05", lua("string.format('%g', 0.00001)"));
        assertEquals("100000", lua("string.format('%g', 100000)"));
        assertEquals("1e+06", lua("string.format('%g', 1000000)"));
        assertEquals("1.23457e+08", lua("string.format('%g', 123456789)"));
        // o expoente que decide entre %e e %f é o do valor já arredondado para P algarismos
        assertEquals("1e+06", lua("string.format('%g', 999999.5)"));
        assertEquals("10", lua("string.format('%.3g', 9.996)"));
        assertEquals("1e+02", lua("string.format('%.2g', 99.5)"));
        assertEquals("0.0001", lua("string.format('%g', 0.00009999995)"));
        assertEquals("0.1", lua("string.format('%.14g', 0.1)"));
        assertEquals("0.30000000000000004", lua("string.format('%.17g', 0.1 + 0.2)"));
        assertEquals("1.00000", lua("string.format('%#g', 1)"));
        assertEquals("3.", lua("string.format('%#.0f', 3)"));
        assertEquals("0.00", lua("string.format('%.2f', 0)"));
    }

    @Test
    public void roundingIsExactAndHalfToEven() {
        // 0,5 e 2,5 são exatos em binário: metade para par, como no C (o Java arredonda para cima).
        assertEquals("0", lua("string.format('%.0f', 0.5)"));
        assertEquals("2", lua("string.format('%.0f', 1.5)"));
        assertEquals("2", lua("string.format('%.0f', 2.5)"));
        assertEquals("4", lua("string.format('%.0f', 3.5)"));
        // 2,675 vale 2,67499999999999982236431605997495353221893310546875 em binário.
        assertEquals("2.67", lua("string.format('%.2f', 2.675)"));
        assertEquals("1.00", lua("string.format('%.2f', 0.999)"));
        assertEquals("10.0", lua("string.format('%.1f', 9.96)"));
        assertEquals("1.0e+01", lua("string.format('%.1e', 9.96)"));
        assertEquals("10", lua("string.format('%.2g', 9.96)"));
    }

    @Test
    public void infinityAndNanNeverGetZeroPadding() {
        assertEquals("inf", lua("string.format('%f', math.huge)"));
        assertEquals("-inf", lua("string.format('%f', -math.huge)"));
        assertEquals("   inf", lua("string.format('%06.1f', math.huge)"));
        assertEquals("INF", lua("string.format('%E', math.huge)"));
        assertEquals("+inf", lua("string.format('%+g', math.huge)"));
        assertEquals("nan", lua("string.format('%f', 0/0)"));
        assertEquals("NAN", lua("string.format('%G', 0/0)"));
        assertEquals(" nan", lua("string.format('% f', 0/0)"));
    }

    @Test
    public void hexadecimalFloatsFollowGlibc() {
        assertEquals("0x1p+0", lua("string.format('%a', 1)"));
        assertEquals("0x1p-1", lua("string.format('%a', 0.5)"));
        assertEquals("0x1.8p+0", lua("string.format('%a', 1.5)"));
        assertEquals("0x1.999999999999ap-4", lua("string.format('%a', 0.1)"));
        assertEquals("0X1.8P+1", lua("string.format('%A', 3)"));
        assertEquals("0x1.80p+0", lua("string.format('%.2a', 1.5)"));
        assertEquals("0x2p+0", lua("string.format('%.0a', 1.5)"));
        assertEquals("0x1.0p+0", lua("string.format('%.1a', 1.03125)"));
        assertEquals("0x1.2p+0", lua("string.format('%.1a', 1.09375)"));
        assertEquals("0x0p+0", lua("string.format('%a', 0)"));
        assertEquals("-0x1.ffp+7", lua("string.format('%a', -255.5)"));
    }

    // ------------------------------------------------------------------ inteiros

    @Test
    public void integersUseSixtyFourBits() {
        assertEquals("1099511627776", lua("string.format('%d', 2^40)"));
        assertEquals("9007199254740992", lua("string.format('%d', 2^53)"));
        assertEquals("-9007199254740992", lua("string.format('%d', -2^53)"));
        assertEquals("3000000000", lua("string.format('%d', 3000000000)"));
        assertEquals("-2147483649", lua("string.format('%i', -2147483649)"));
        assertEquals("-9223372036854775808", lua("string.format('%d', -2^63)"));
        assertEquals("ffffffffffffffff", lua("string.format('%x', -1)"));
        assertEquals("FFFFFFFFFFFFFFFE", lua("string.format('%X', -2)"));
        assertEquals("18446744073709551615", lua("string.format('%u', -1)"));
        assertEquals("1777777777777777777777", lua("string.format('%o', -1)"));
        assertEquals("10", lua("string.format('%d', '10')"));
        assertEquals("3", lua("string.format('%d', 3.9)"));
        assertEquals("-3", lua("string.format('%d', -3.9)"));
    }

    @Test
    public void integerFlagsWidthAndPrecision() {
        assertEquals("   42|", lua("string.format('%5d|', 42)"));
        assertEquals("42   |", lua("string.format('%-5d|', 42)"));
        assertEquals("00042|", lua("string.format('%05d|', 42)"));
        assertEquals("-0042|", lua("string.format('%05d|', -42)"));
        assertEquals("+42", lua("string.format('%+d', 42)"));
        assertEquals(" 42", lua("string.format('% d', 42)"));
        assertEquals("  007", lua("string.format('%5.3d', 7)"));
        assertEquals("  007", lua("string.format('%05.3d', 7)"));
        assertEquals("", lua("string.format('%.0d', 0)"));
        assertEquals("0xff", lua("string.format('%#x', 255)"));
        assertEquals("0XFF", lua("string.format('%#X', 255)"));
        assertEquals("0", lua("string.format('%#x', 0)"));
        assertEquals("010", lua("string.format('%#o', 8)"));
        assertEquals("0x00ff", lua("string.format('%#06x', 255)"));
        // + e espaço só valem para conversões com sinal (%d/%i); em %u/%x/%o o C os ignora.
        assertEquals("5", lua("string.format('%+u', 5)"));
        assertEquals("ff", lua("string.format('% x', 255)"));
        assertEquals("ff", lua("string.format('%+x', 255)"));
        assertEquals("10", lua("string.format('%+o', 8)"));
    }

    @Test
    public void charsAndQuotedStrings() {
        assertEquals("A", lua("string.format('%c', 65)"));
        assertEquals("  A", lua("string.format('%3c', 65)"));
        assertEquals("A  |", lua("string.format('%-3c|', 65)"));
        assertEquals(1, lua("string.format('%c', 0)").length());
        assertEquals("\"a\\\nb\\\"c\\\\d\"", lua("string.format('%q', 'a\\nb\"c\\\\d')"));
        assertEquals("\"\\0011\"", lua("string.format('%q', '\\0011')"));
        assertEquals("\"\\1x\"", lua("string.format('%q', '\\1x')"));
        assertEquals("\"\\127\"", lua("string.format('%q', '\\127')"));
        assertEquals("\"\\31\"", lua("string.format('%q', '\\31')"));
        assertEquals("\" \"", lua("string.format('%q', ' ')"));
    }

    // ------------------------------------------------------------------ %s e tostring

    @Test
    public void stringConversionAcceptsAnyValue() {
        assertEquals("nil", lua("string.format('%s', nil)"));
        assertEquals("true", lua("string.format('%s', true)"));
        assertEquals("false", lua("string.format('%s', false)"));
        assertEquals("12", lua("string.format('%s', 12)"));
        assertEquals("0.33333333333333", lua("string.format('%s', 1/3)"));
        assertTrue(lua("string.format('%s', {})").startsWith("table: "));
        assertTrue(lua("string.format('%s', print)").startsWith("function: "));
        assertEquals("<ponto 3,4>", lua("string.format('%s', setmetatable({}, {__tostring = function() return '<ponto 3,4>' end}))"));
        assertEquals("x=nil y=true", lua("string.format('x=%s y=%s', nil, true)"));
    }

    @Test
    public void tostringMetamethodMustReturnAString() {
        assertError("'__tostring' must return a string", "tostring(setmetatable({}, {__tostring = function() return {} end}))");
        assertError("'__tostring' must return a string", "string.format('%s', setmetatable({}, {__tostring = function() return nil end}))");
        assertEquals("7", lua("tostring(setmetatable({}, {__tostring = function() return 7 end}))"));
    }

    @Test
    public void nanNeverPrintsASignEvenWithTheSignBitSet() {
        double negativeNan = Double.longBitsToDouble(0xFFF8000000000000L);
        assertTrue(Double.isNaN(negativeNan));
        assertEquals("nan", format("%f", LuaValue.valueOf(negativeNan)));
        assertEquals("nan", format("%g", LuaValue.valueOf(negativeNan)));
        assertEquals("  nan", format("%5.1e", LuaValue.valueOf(negativeNan)));
        assertEquals("+nan", format("%+f", LuaValue.valueOf(negativeNan)));
        assertEquals("nan", NornsStringFormat.doubleToString(negativeNan));
    }

    @Test
    public void stringWidthPrecisionAndEmbeddedZeros() {
        assertEquals("        ab|", lua("string.format('%10s|', 'ab')"));
        assertEquals("ab        |", lua("string.format('%-10s|', 'ab')"));
        assertEquals("abc|", lua("string.format('%.3s|', 'abcdef')"));
        assertEquals("   ab|", lua("string.format('%5.2s|', 'abcdef')"));
        assertEquals("", lua("string.format('%.0s', 'abc')"));
        assertEquals(5, lua("string.format('%s', 'a\\0b\\0c')").length());
        assertEquals("héllo", lua("string.format('%s', 'h\\195\\169llo')"));
        assertError("string contains zeros", "string.format('%5s', 'a\\0b')");
        String longText = new String(new char[150]).replace('\0', 'z');
        assertEquals(longText, lua("string.format('%5s', '" + longText + "')"));
    }

    @Test
    public void tostringWritesFourteenDigits() {
        assertEquals("0.33333333333333", lua("tostring(1/3)"));
        assertEquals("0.1", lua("tostring(0.1)"));
        assertEquals("0.3", lua("tostring(0.1 + 0.2)"));
        assertEquals("3.5", lua("tostring(3.5)"));
        assertEquals("-2.25", lua("tostring(-2.25)"));
        assertEquals("1e+20", lua("tostring(1e20)"));
        assertEquals("9.007199254741e+15", lua("tostring(2^53)"));
        assertEquals("1099511627776", lua("tostring(2^40)"));
        assertEquals("42", lua("tostring(42)"));
        assertEquals("inf", lua("tostring(math.huge)"));
        assertEquals("-inf", lua("tostring(-math.huge)"));
        assertEquals("nan", lua("tostring(0/0)"));
        assertEquals("nil", lua("tostring(nil)"));
        assertEquals("true", lua("tostring(true)"));
        assertEquals("abc", lua("tostring('abc')"));
        assertEquals("<x>", lua("tostring(setmetatable({}, {__tostring = function() return '<x>' end}))"));
        assertTrue(lua("tostring({})").startsWith("table: "));
    }

    @Test
    public void printUsesTheReplacedTostring() throws Exception {
        Globals g = globals();
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        g.STDOUT = new java.io.PrintStream(captured, true, "ISO-8859-1");
        run(g, "print(1/3, 7, 'x')");
        assertEquals("0.33333333333333\t7\tx", captured.toString("ISO-8859-1").trim());
    }

    // ------------------------------------------------------------------ erros

    @Test
    public void errorsNameTheArgumentLikeLua() {
        assertError("bad argument #2 to 'format' (no value)", "string.format('%d')");
        assertError("bad argument #3 to 'format' (no value)", "string.format('%d %d', 1)");
        assertError("bad argument #2 to 'format' (number expected, got string)", "string.format('%d', 'x')");
        assertError("bad argument #2 to 'format' (number expected, got nil)", "string.format('%f', nil)");
        assertError("bad argument #2 to 'format' (number expected, got table)", "string.format('%x', {})");
        assertError("bad argument #2 to 'format' (string expected, got nil)", "string.format('%q', nil)");
        assertError("bad argument #1 to 'format' (string expected, got nil)", "string.format(nil)");
        assertError("bad argument #1 to 'format' (string expected, got table)", "string.format({})");
        assertError("number has no integer representation", "string.format('%d', 1e30)");
        assertError("number has no integer representation", "string.format('%d', 0/0)");
        assertError("number has no integer representation", "string.format('%d', math.huge)");
        assertError("number has no integer representation", "string.format('%d', 2^63)");
        assertError("number has no integer representation", "string.format('%x', -2^63 - 2048)");
    }

    @Test
    public void malformedSpecificationsAreRejected() {
        assertError("invalid option '%y' to 'format'", "string.format('%y', 1)");
        assertError("invalid option '%' to 'format'", "string.format('abc %', 1)");
        assertError("bad argument #2 to 'format' (no value)", "string.format('%5%')");
        assertError("invalid format (repeated flags)", "string.format('%------d', 1)");
        assertError("invalid format (width or precision too long)", "string.format('%100d', 1)");
        assertError("invalid format (width or precision too long)", "string.format('%.100f', 1)");
        assertEquals("5     |", lua("string.format('%-----6d|', 5)"));
        assertEquals("100%", lua("string.format('%d%%', 100)"));
        assertEquals("100%", lua("string.format('100%%')"));
        assertEquals("12", lua("string.format(12)"));
        assertEquals("a1b2", lua("string.format('a%db%d', 1, 2, 3)"));
    }

    // ------------------------------------------------------------------ integração

    @Test
    public void methodSyntaxAndMetatableUseTheReplacement() {
        assertEquals("002.5", lua("('%05.1f'):format(2.5)"));
        assertEquals("  7", lua("('%3d'):format(7)"));
        assertEquals("ab", lua("string.rep('a', 1) .. ('%s'):format('b')"));
    }

    @Test
    public void installReplacesStringFormatAndTostringOnly() {
        Globals plain = JsePlatform.standardGlobals();
        LuaValue originalFormat = plain.get("string").get("format");
        LuaValue originalTostring = plain.get("tostring");
        LuaValue originalRep = plain.get("string").get("rep");
        NornsStringFormat.install(plain);
        assertNotSame(originalFormat, plain.get("string").get("format"));
        assertNotSame(originalTostring, plain.get("tostring"));
        assertSame(originalRep, plain.get("string").get("rep"));
    }

    @Test
    public void hostInstallsTheFormatterRightAfterTheStandardGlobals() throws Exception {
        String host = read("app/src/main/java/org/nornsmobile/app/LuaHost.java");
        int standard = host.indexOf("Globals g = JsePlatform.standardGlobals();");
        int install = host.indexOf("NornsStringFormat.install(g);");
        int hostFunctions = host.indexOf("installHost(g);", standard);
        assertTrue("LuaHost deve criar as globais com JsePlatform.standardGlobals()", standard > 0);
        assertTrue("LuaHost deve chamar NornsStringFormat.install(g)", install > 0);
        assertTrue("NornsStringFormat.install(g) vem logo depois das globais e antes do installHost",
                standard < install && install < hostFunctions);
    }

    @Test
    public void doubleToStringMatchesTheLuaFormat() {
        assertEquals("0.33333333333333", NornsStringFormat.doubleToString(1.0 / 3));
        assertEquals("1e+15", NornsStringFormat.doubleToString(1e15));
        assertEquals("1.2345678901234e+14", NornsStringFormat.doubleToString(123456789012345.0));
        assertEquals("12345678901234", NornsStringFormat.doubleToString(12345678901234.0));
        assertEquals("-0", NornsStringFormat.doubleToString(-0.0));
        assertEquals("-2.5", NornsStringFormat.doubleToString(-2.5));
        assertEquals("nan", NornsStringFormat.doubleToString(Double.NaN));
        assertEquals("-inf", NornsStringFormat.doubleToString(Double.NEGATIVE_INFINITY));
    }
}
