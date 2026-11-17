package com.smartprompter.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 纯 Java 文档读写工具：不依赖 POI 等重型库，避免在低端机上引入额外内存压力。
 * 支持 txt / markdown 文本读取清洗，以及最小 OOXML(docx) 的解析与生成。
 */
public final class DocxUtils {

    private DocxUtils() {}

    /** 一次性读取流为 UTF-8 字符串。 */
    public static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = input.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 从 docx(OOXML) 包中抽取正文纯文本，段落间用换行分隔。 */
    public static String readTextFromDocx(InputStream input) throws IOException {
        String xml = null;
        ZipInputStream zis = new ZipInputStream(input, StandardCharsets.UTF_8);
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    xml = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                    break;
                }
            }
        } finally {
            try { zis.close(); } catch (IOException ignored) {}
        }
        if (xml == null) return "";
        xml = xml.replaceAll("</w:p>", "\n");
        xml = xml.replaceAll("<w:tab[^>]*/>", "\t");
        xml = xml.replaceAll("<[^>]+>", "");
        return decodeXmlEntities(xml).trim();
    }

    /** 清洗 Markdown 标记符号，保留纯口播文本。 */
    public static String stripMarkdown(String raw) {
        if (raw == null) return "";
        String[] lines = raw.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String l = line.trim();
            // 去掉 Markdown 标题井号、引用符、列表符号
            l = l.replaceAll("^#{1,6}\\s*", "");
            l = l.replaceAll("^>\\s*", "");
            l = l.replaceAll("^[-+*]\\s+", "");
            // 去掉加粗/斜体/行内代码包裹符号
            l = l.replaceAll("\\*\\*", "").replaceAll("__", "");
            l = l.replaceAll("\\*", "").replaceAll("`", "");
            out.append(l).append("\n");
        }
        return out.toString().trim();
    }

    /** 生成最小可用的 docx 包，标题作为首段加粗，正文按换行分段。 */
    public static void writeDocx(OutputStream out, String title, String content) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8);
        try {
            putEntry(zos, "[Content_Types].xml", CONTENT_TYPES_XML);
            putEntry(zos, "_rels/.rels", RELS_XML);

            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(buildDocumentXml(title, content).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } finally {
            try { zos.close(); } catch (IOException ignored) {}
        }
    }

    private static void putEntry(ZipOutputStream zos, String name, String data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String buildDocumentXml(String title, String content) {
        StringBuilder body = new StringBuilder();
        if (title != null && !title.trim().isEmpty()) {
            body.append("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">")
                    .append(escapeXml(title.trim()))
                    .append("</w:t></w:r></w:p>");
        }
        String[] paragraphs = (content == null ? "" : content).split("\n", -1);
        for (String p : paragraphs) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(escapeXml(p))
                    .append("</w:t></w:r></w:p>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + body.toString() + "</w:body></w:document>";
    }

    private static final String CONTENT_TYPES_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "</Types>";

    private static final String RELS_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
            + "</Relationships>";

    static String decodeXmlEntities(String s) {
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'");
    }

    static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
