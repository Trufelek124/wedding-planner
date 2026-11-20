package io.github.trufelek124.weddingplanner;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * PDF documents the tests make themselves, with the same library the server reads them with (nothing is committed, and nothing
 * made-up here is a real person's). The text is drawn with a font made for the occasion (a Type 3 font whose glyphs are empty
 * boxes and whose codes are mapped to characters by its own table), so that Polish and Ukrainian letters are in a file on any
 * machine, and no font of the machine is needed or looked at.
 */
public final class TestPdfs {

    private TestPdfs() {}

    /** A document with one page for each text; the lines of a page are the lines of its text (at most about eighty characters each). */
    public static byte[] withPages(String... pages) {
        return build(null, pages, false);
    }

    /** As {@link #withPages}, and the file asks for this password before it is opened. */
    public static byte[] locked(String password, String... pages) {
        return build(password, pages, false);
    }

    /** A document of {@code count} pages; page {@code n} says {@code PAGE-n-MARKER}. */
    public static byte[] ofPages(int count) {
        String[] pages = new String[count];
        for (int n = 1; n <= count; n++) {
            pages[n - 1] = "PAGE-" + n + "-MARKER";
        }
        return withPages(pages);
    }

    /** A document whose only page is a picture: nothing in it is text. */
    public static byte[] onlyAPicture() {
        return build(null, new String[] {"x"}, true);
    }

    /** A document in one of the fourteen standard fonts, which a file does not carry: the machine's fonts are looked at to draw it. English only. */
    public static byte[] inAStandardFont(String text) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A document that tries what a file can try: a script that runs when it is opened, a link to this address that a viewer
     * would follow, and an action that would launch a program. Reading its text must do none of it.
     */
    public static byte[] thatTriesThings(String linkAddress, String... pages) {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(withPages(pages))) {
            COSDictionary script = new COSDictionary();
            script.setItem(COSName.TYPE, COSName.getPDFName("Action"));
            script.setItem(COSName.S, COSName.getPDFName("JavaScript"));
            script.setString(COSName.getPDFName("JS"), "app.launchURL('" + linkAddress + "/script');");
            document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("OpenAction"), script);
            org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link =
                    new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
            link.setRectangle(new PDRectangle(40, 700, 300, 100));
            org.apache.pdfbox.pdmodel.interactive.action.PDActionURI uri = new org.apache.pdfbox.pdmodel.interactive.action.PDActionURI();
            uri.setURI(linkAddress + "/link");
            link.setAction(uri);
            org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink launch =
                    new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
            launch.setRectangle(new PDRectangle(40, 500, 300, 100));
            COSDictionary program = new COSDictionary();
            program.setItem(COSName.TYPE, COSName.getPDFName("Action"));
            program.setItem(COSName.S, COSName.getPDFName("Launch"));
            program.setString(COSName.F, "not-a-program.example");
            launch.getCOSObject().setItem(COSName.A, program);
            document.getPage(0).setAnnotations(List.of(link, launch));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The first {@code bytes} bytes of a file: what an interrupted download leaves. */
    public static byte[] cutOffAt(byte[] file, int bytes) {
        return Arrays.copyOf(file, bytes);
    }

    private static byte[] build(String password, String[] pages, boolean onlyAPicture) {
        try (PDDocument document = new PDDocument()) {
            Map<Character, Integer> codes = new LinkedHashMap<>();
            for (String page : pages) {
                for (char c : page.toCharArray()) {
                    if (c != '\n' && !codes.containsKey(c)) {
                        codes.put(c, codes.size() + 1);
                    }
                }
            }
            if (codes.size() > 250) {
                throw new IllegalArgumentException("too many different characters for one test font");
            }
            if (codes.isEmpty()) {
                codes.put(' ', 1);
            }
            PDFont font = PDFontFactory.createFont(fontOf(document, codes));
            for (String text : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    if (onlyAPicture) {
                        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
                        PDImageXObject picture = LosslessFactory.createFromImage(document, image);
                        content.drawImage(picture, 50, 600, 200, 200);
                        continue;
                    }
                    PDResources resources = new PDResources();
                    page.setResources(resources);
                    String name = resources.add(font).getName();
                    StringBuilder raw = new StringBuilder("BT /" + name + " 10 Tf 12 TL 50 780 Td\n");
                    for (String line : text.split("\n", -1)) {
                        raw.append('<');
                        for (char c : line.toCharArray()) {
                            raw.append(String.format("%02X", codes.get(c)));
                        }
                        raw.append("> Tj T*\n");
                    }
                    raw.append("ET\n");
                    content.appendRawCommands(raw.toString());
                }
            }
            if (password != null) {
                AccessPermission permissions = new AccessPermission();
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-" + password, password, permissions);
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A Type 3 font: every character of {@code codes} is a glyph that is an empty box 600 wide, and a table says which character it is. */
    private static COSDictionary fontOf(PDDocument document, Map<Character, Integer> codes) throws IOException {
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE3);
        COSArray box = new COSArray();
        for (int value : new int[] {0, 0, 1000, 1000}) {
            box.add(COSInteger.get(value));
        }
        font.setItem(COSName.FONT_BBOX, box);
        COSArray matrix = new COSArray();
        for (float value : new float[] {0.001f, 0, 0, 0.001f, 0, 0}) {
            matrix.add(new COSFloat(value));
        }
        font.setItem(COSName.FONT_MATRIX, matrix);
        COSDictionary procs = new COSDictionary();
        COSArray differences = new COSArray();
        differences.add(COSInteger.get(1));
        COSArray widths = new COSArray();
        for (int code = 1; code <= codes.size(); code++) {
            COSName glyph = COSName.getPDFName("g" + code);
            COSStream proc = document.getDocument().createCOSStream();
            try (OutputStream out = proc.createOutputStream()) {
                out.write("600 0 0 0 500 700 d1\n0 0 500 700 re f\n".getBytes(StandardCharsets.US_ASCII));
            }
            procs.setItem(glyph, proc);
            differences.add(glyph);
            widths.add(COSInteger.get(600));
        }
        font.setItem(COSName.CHAR_PROCS, procs);
        COSDictionary encoding = new COSDictionary();
        encoding.setItem(COSName.TYPE, COSName.ENCODING);
        encoding.setItem(COSName.DIFFERENCES, differences);
        font.setItem(COSName.ENCODING, encoding);
        font.setItem(COSName.FIRST_CHAR, COSInteger.get(1));
        font.setItem(COSName.LAST_CHAR, COSInteger.get(codes.size()));
        font.setItem(COSName.WIDTHS, widths);
        COSStream toUnicode = document.getDocument().createCOSStream();
        try (OutputStream out = toUnicode.createOutputStream()) {
            out.write(toUnicodeTable(codes).getBytes(StandardCharsets.US_ASCII));
        }
        font.setItem(COSName.TO_UNICODE, toUnicode);
        return font;
    }

    private static String toUnicodeTable(Map<Character, Integer> codes) {
        List<String> entries = new ArrayList<>();
        codes.forEach((character, code) -> entries.add(String.format("<%02X> <%04X>", code, (int) character)));
        StringBuilder table = new StringBuilder(
                "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Test-UCS def /CMapType 2 def\n"
                        + "1 begincodespacerange <01> <FF> endcodespacerange\n");
        for (int from = 0; from < entries.size(); from += 100) {
            List<String> chunk = entries.subList(from, Math.min(entries.size(), from + 100));
            table.append(chunk.size()).append(" beginbfchar\n").append(String.join("\n", chunk)).append("\nendbfchar\n");
        }
        return table.append("endcmap CMapName currentdict /CMap defineresource pop end end\n").toString();
    }
}
