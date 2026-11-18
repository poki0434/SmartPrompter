package com.smartprompter.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 纯原生草稿导入与导出工具（支持 Word .docx / Markdown .md / TXT .txt，零第三方库依赖）
 */
public class DraftFileHelper {

    public static String getFileName(ContentResolver cr, Uri uri) {
        String name = "导入草稿";
        try (Cursor cursor = cr.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {}
        return name;
    }

    
    public static boolean hasMarkdownSyntax(String text) {
        if (text == null || text.isEmpty()) return false;
        return java.util.regex.Pattern.compile(
                "(?m)(^\\s{0,3}#{1,6}\\s+|__[^_\\n]+__|^\\s{0,3}>\\s|^\\s{0,3}[-*+]\\s|\\[[^\\]]+\\]\\([^)]+\\))"
        ).matcher(text).find();
    }

    public static String parseMarkdownToPrompter(String raw) {
        if (raw == null) return "";
        String s = raw.replace("\r\n", "\n");
        s = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "");
        s = s.replaceAll("(?m)^\\s{0,3}>\\s?", "");
        s = s.replaceAll("(?m)^\\s{0,3}([-*+]|\\d+\\.)\\s+", "");
        s = s.replaceAll("(?m)^\\s*([-*_]){3,}\\s*$", "");
        s = s.replaceAll("__([^_\\n]+)__", "**$1**");
        s = s.replaceAll("(?<!\\*)\\*(?!\\*)([^*\\n]+)(?<!\\*)\\*(?!\\*)", "$1");
        s = s.replaceAll("`([^`\\n]+)`", "$1");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    public static String stripExtension(String filename) {
        if (filename == null) return "导入草稿";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    public static String readTextFromUri(ContentResolver cr, Uri uri, String fileName) throws Exception {
        String lower = fileName != null ? fileName.toLowerCase() : "";
        try (InputStream is = cr.openInputStream(uri)) {
            if (is == null) throw new Exception("无法打开文件");
            if (lower.endsWith(".docx")) {
                return readDocx(is);
            } else {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
                String raw = sb.toString();
                if (lower.endsWith(".md") || hasMarkdownSyntax(raw)) {
                    return parseMarkdownToPrompter(raw);
                }
                return raw;
            }
        }
    }

    private static String readDocx(InputStream is) throws Exception {
        byte[] docXmlBytes = null;
        try (ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = zis.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                    }
                    docXmlBytes = baos.toByteArray();
                    break;
                }
            }
        }
        if (docXmlBytes == null) throw new Exception("无效的 Word(.docx) 文件");

        StringBuilder result = new StringBuilder();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(docXmlBytes), "UTF-8");
        int eventType = parser.getEventType();
        StringBuilder para = new StringBuilder();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            if (eventType == XmlPullParser.START_TAG) {
                if ("p".equals(tag)) {
                    para.setLength(0);
                } else if ("t".equals(tag)) {
                    para.append(parser.nextText());
                } else if ("br".equals(tag)) {
                    para.append("\n");
                }
            } else if (eventType == XmlPullParser.END_TAG && "p".equals(tag)) {
                if (result.length() > 0) result.append("\n");
                result.append(para);
            }
            eventType = parser.next();
        }
        return result.toString();
    }

    public static void writeExportToUri(ContentResolver cr, Uri uri, String title, String content, String format) throws Exception {
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new Exception("无法写入目标文件");
            if ("docx".equals(format)) {
                writeDocx(os, title, content);
            } else if ("md".equals(format)) {
                String md = "# " + title + "\n\n" + content;
                os.write(md.getBytes(StandardCharsets.UTF_8));
            } else {
                String txt = title + "\n\n" + content;
                os.write(txt.getBytes(StandardCharsets.UTF_8));
            }
            os.flush();
        }
    }

    private static void writeDocx(OutputStream os, String title, String content) throws Exception {
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";

        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";

        StringBuilder doc = new StringBuilder();
        doc.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        doc.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        doc.append("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">")
                .append(escapeXml(title)).append("</w:t></w:r></w:p>");

        String[] lines = (content == null ? "" : content).split("\r?\n", -1);
        for (String line : lines) {
            doc.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(escapeXml(line)).append("</w:t></w:r></w:p>");
        }
        doc.append("</w:body></w:document>");

        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write(contentTypes.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("_rels/.rels"));
            zos.write(rels.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(doc.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
