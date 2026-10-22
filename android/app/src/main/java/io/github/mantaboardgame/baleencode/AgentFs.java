package io.github.mantaboardgame.baleencode;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * File operations for the assistant, confined to ONE folder the user picked in the system
 * folder picker (Storage Access Framework). Android itself refuses access outside that tree,
 * and every path here is relative to it ("..", absolute paths and backslashes are rejected).
 * Nothing is ever erased: "delete" moves into a trash folder and overwritten files are backed up.
 */
final class AgentFs {
    static final String TRASH = ".assistant-trash";
    static final String BACKUP = ".assistant-backup";
    static final int MAX_READ_CHARS = 40_000;
    static final int MAX_TEXT_BYTES = 5 * 1024 * 1024;
    static final int MAX_WRITE_BYTES = 20 * 1024 * 1024;
    private static final String[] COLS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    static final class Node {
        String docId, name, mime;
        long size, modified;
        boolean isDir() { return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime); }
    }

    private final ContentResolver cr;
    private final Uri tree;
    private final String rootId;

    AgentFs(ContentResolver cr, Uri tree) {
        this.cr = cr;
        this.tree = tree;
        this.rootId = DocumentsContract.getTreeDocumentId(tree);
    }

    // ---------- paths ----------

    static List<String> segments(String rel) throws Exception {
        List<String> out = new ArrayList<>();
        if (rel == null) return out;
        if (rel.indexOf('\0') >= 0 || rel.indexOf('\\') >= 0 || rel.startsWith("/") || rel.matches("^[A-Za-z]:.*")) {
            throw new Exception("Use a path relative to the chosen folder, with / separators.");
        }
        for (String s : rel.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) throw new Exception("\"..\" is not allowed: only the chosen folder can be used.");
            out.add(s);
        }
        return out;
    }

    private Uri docUri(String docId) { return DocumentsContract.buildDocumentUriUsingTree(tree, docId); }

    private Node rootNode() {
        Node n = new Node();
        n.docId = rootId; n.name = ""; n.mime = DocumentsContract.Document.MIME_TYPE_DIR;
        return n;
    }

    private static Node fromCursor(Cursor c) {
        Node n = new Node();
        n.docId = c.getString(0); n.name = c.getString(1); n.mime = c.getString(2);
        n.size = c.isNull(3) ? 0 : c.getLong(3);
        n.modified = c.isNull(4) ? 0 : c.getLong(4);
        return n;
    }

    List<Node> children(String docId) {
        List<Node> out = new ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor c = cr.query(uri, COLS, null, null, null)) {
            while (c != null && c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    private Node child(Node dir, String name) {
        for (Node n : children(dir.docId)) if (name.equals(n.name)) return n;
        return null;
    }

    /** Returns null when the path does not exist */
    Node resolve(String rel) throws Exception {
        Node cur = rootNode();
        for (String s : segments(rel)) {
            if (!cur.isDir()) return null;
            cur = child(cur, s);
            if (cur == null) return null;
        }
        return cur;
    }

    private Node need(String rel) throws Exception {
        Node n = resolve(rel);
        if (n == null) throw new Exception("Not found: " + rel);
        return n;
    }

    private Node mkdirs(List<String> segs) throws Exception {
        Node cur = rootNode();
        for (String s : segs) {
            Node next = child(cur, s);
            if (next == null) {
                Uri made = DocumentsContract.createDocument(cr, docUri(cur.docId), DocumentsContract.Document.MIME_TYPE_DIR, s);
                if (made == null) throw new Exception("Could not create the folder " + s);
                next = child(cur, s);
                if (next == null) throw new Exception("Could not create the folder " + s);
            } else if (!next.isDir()) {
                throw new Exception("\"" + s + "\" is a file, not a folder.");
            }
            cur = next;
        }
        return cur;
    }

    // ---------- text helpers ----------

    private static byte[] readBytes(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new Exception("The file is too large for this tool.");
        }
        in.close();
        return out.toByteArray();
    }

    private byte[] bytesOf(Node n, int max) throws Exception {
        InputStream in = cr.openInputStream(docUri(n.docId));
        if (in == null) throw new Exception("Could not open the file.");
        return readBytes(in, max);
    }

    static boolean looksBinary(byte[] b) {
        if (b.length >= 2 && ((b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe || (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff)) return false;
        int n = Math.min(b.length, 8000);
        for (int i = 0; i < n; i++) if (b[i] == 0) return true;
        return false;
    }

    static String decode(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("Shift_JIS"));
        }
    }

    static boolean sensitive(String rel) {
        String p = rel.toLowerCase(Locale.ROOT);
        return p.matches("(^|.*/)\\.env(\\.[^/]*)?")
                || p.matches(".*\\.(pem|pfx|p12|key|kdbx|ppk)")
                || p.matches("(^|.*/)(id_rsa|id_ed25519|id_ecdsa)[^/]*")
                || p.matches("(^|.*/)(apikey|api_key|secrets?|credentials?)([.\\-_][^/]*)?")
                || p.matches("(^|.*/)\\.(ssh|aws|gnupg)(/.*)?");
    }

    private static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder("^");
        for (char ch : g.toCharArray()) {
            if (ch == '*') sb.append("[^/]*");
            else if (ch == '?') sb.append("[^/]");
            else if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) sb.append('\\').append(ch);
            else sb.append(ch);
        }
        return Pattern.compile(sb.append('$').toString(), Pattern.CASE_INSENSITIVE);
    }

    private static String lastName(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }

    // ---------- operations ----------

    String exists(String rel) throws Exception {
        Node n = resolve(rel);
        return n == null ? "none" : n.isDir() ? "dir" : "file";
    }

    String list(String rel) throws Exception {
        Node d = need(rel);
        if (!d.isDir()) throw new Exception("This is a file. Use read_file.");
        List<Node> kids = children(d.docId);
        Collections.sort(kids, Comparator.comparing((Node n) -> !n.isDir()).thenComparing(n -> n.name.toLowerCase(Locale.ROOT)));
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Node n : kids) {
            if (n.name.equals(TRASH) || n.name.equals(BACKUP)) continue;
            if (count++ >= 500) { sb.append("[… more entries not shown]\n"); break; }
            sb.append(n.isDir() ? "[dir] " : "      ").append(n.name);
            if (!n.isDir()) sb.append("  (").append(n.size).append(" bytes)");
            sb.append('\n');
        }
        return count == 0 ? "(empty)" : sb.toString().trim();
    }

    /** kind: text (default) — images are handled by the caller through readImage */
    String read(String rel, int offset) throws Exception {
        Node n = need(rel);
        if (n.isDir()) throw new Exception("This is a folder. Use list_dir.");
        if (n.size > 200L * 1024 * 1024) throw new Exception("The file is too large (over 200 MB).");
        String ext = ext(n.name);
        if (ext.matches("zip|exe|dll|apk")) throw new Exception("." + ext + " files cannot be read.");
        byte[] b = bytesOf(n, MAX_TEXT_BYTES);
        if (looksBinary(b)) throw new Exception("This looks like a binary file, so it was not read.");
        String text = decode(b);
        int start = Math.max(0, Math.min(offset, text.length()));
        int end = Math.min(text.length(), start + MAX_READ_CHARS);
        String part = text.substring(start, end);
        if (end < text.length()) part += "\n[… " + (text.length() - end) + " more characters. Call again with offset=" + end + ".]";
        return part.isEmpty() ? "(empty file)" : part;
    }

    byte[] readImage(String rel) throws Exception {
        Node n = need(rel);
        if (n.isDir()) throw new Exception("This is a folder.");
        return bytesOf(n, 30 * 1024 * 1024);
    }

    static boolean isImage(String name) { return ext(name).matches("png|jpg|jpeg|bmp|gif|webp"); }

    String readAll(String rel) throws Exception {
        Node n = need(rel);
        if (n.isDir()) throw new Exception("This is a folder.");
        byte[] b = bytesOf(n, MAX_TEXT_BYTES);
        if (looksBinary(b)) throw new Exception("This looks like a binary file.");
        return decode(b);
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** Create or overwrite a file (an existing file is backed up first) */
    String write(String rel, String content) throws Exception {
        List<String> segs = segments(rel);
        if (segs.isEmpty()) throw new Exception("A file path is required.");
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_WRITE_BYTES) throw new Exception("Content is too large.");
        String name = segs.get(segs.size() - 1);
        if (name.equals(TRASH) || name.equals(BACKUP)) throw new Exception("That name is reserved.");
        Node existing = resolve(rel);
        if (existing != null && existing.isDir()) throw new Exception("That path is a folder.");
        if (existing != null) {
            backup(existing);
            try (OutputStream os = cr.openOutputStream(docUri(existing.docId), "wt")) { os.write(data); }
            return "Wrote " + content.length() + " characters to " + rel;
        }
        Node parent = mkdirs(segs.subList(0, segs.size() - 1));
        String ext = ext(name);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null) mime = "application/octet-stream";
        Uri made = DocumentsContract.createDocument(cr, docUri(parent.docId), mime, name);
        if (made == null) throw new Exception("Could not create the file.");
        // Some providers append an extension to the name; put the exact requested name back
        Node created = child(parent, name);
        if (created == null) {
            for (Node n : children(parent.docId)) {
                if (n.name.startsWith(name) && !n.isDir()) { created = n; break; }
            }
            if (created != null) {
                Uri renamed = DocumentsContract.renameDocument(cr, docUri(created.docId), name);
                if (renamed != null) created.docId = DocumentsContract.getDocumentId(renamed);
            }
        }
        if (created == null) throw new Exception("The file was created but could not be found again.");
        try (OutputStream os = cr.openOutputStream(docUri(created.docId), "wt")) { os.write(data); }
        return "Wrote " + content.length() + " characters to " + rel;
    }

    private void backup(Node file) {
        try {
            if (file.size > MAX_WRITE_BYTES) return;
            Node dir = mkdirs(java.util.Collections.singletonList(BACKUP));
            byte[] b = bytesOf(file, MAX_WRITE_BYTES);
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
            String name = stamp + "-" + file.name;
            Uri made = DocumentsContract.createDocument(cr, docUri(dir.docId), "application/octet-stream", name);
            if (made == null) return;
            try (OutputStream os = cr.openOutputStream(made, "wt")) { os.write(b); }
        } catch (Exception ignored) { }
    }

    String move(String from, String to) throws Exception {
        List<String> fs = segments(from), ts = segments(to);
        if (fs.isEmpty() || ts.isEmpty()) throw new Exception("Both paths are required.");
        Node src = need(from);
        if (resolve(to) != null) throw new Exception("The destination already exists.");
        Node srcParent = resolve(TextUtils.join("/", fs.subList(0, fs.size() - 1)));
        Node dstParent = mkdirs(ts.subList(0, ts.size() - 1));
        String newName = ts.get(ts.size() - 1);
        Uri cur = docUri(src.docId);
        if (!srcParent.docId.equals(dstParent.docId)) {
            cur = DocumentsContract.moveDocument(cr, cur, docUri(srcParent.docId), docUri(dstParent.docId));
            if (cur == null) throw new Exception("This storage does not support moving between folders.");
        }
        if (!newName.equals(src.name)) {
            cur = DocumentsContract.renameDocument(cr, cur, newName);
            if (cur == null) throw new Exception("Could not rename.");
        }
        return "Moved to " + to;
    }

    /** "Delete" = move into the trash folder inside the chosen folder, so it can be restored */
    String trash(String rel) throws Exception {
        List<String> segs = segments(rel);
        if (segs.isEmpty()) throw new Exception("The chosen folder itself cannot be deleted.");
        Node src = need(rel);
        Node parent = resolve(TextUtils.join("/", segs.subList(0, segs.size() - 1)));
        Node trash = mkdirs(java.util.Collections.singletonList(TRASH));
        Uri moved = DocumentsContract.moveDocument(cr, docUri(src.docId), docUri(parent.docId), docUri(trash.docId));
        if (moved == null) throw new Exception("This storage cannot move files to a trash folder, so nothing was deleted.");
        return "Moved " + rel + " to " + TRASH + "/ inside the chosen folder. It can be restored from there.";
    }

    String find(String rel, String pattern) throws Exception {
        Node start = need(rel);
        Pattern re = glob(pattern.matches(".*[*?].*") ? pattern : "*" + pattern + "*");
        List<String> hits = new ArrayList<>();
        long until = System.currentTimeMillis() + 20_000;
        int visited = 0;
        boolean cut = false;
        ArrayDeque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{start, TextUtils.join("/", segments(rel))});
        while (!stack.isEmpty() && !cut) {
            Object[] top = stack.pop();
            Node dir = (Node) top[0];
            String base = (String) top[1];
            for (Node n : children(dir.docId)) {
                if (n.name.equals(TRASH) || n.name.equals(BACKUP)) continue;
                String path = base.isEmpty() ? n.name : base + "/" + n.name;
                if (n.isDir()) stack.push(new Object[]{n, path});
                else if (re.matcher(n.name).matches()) hits.add(path);
                if (hits.size() >= 300 || ++visited > 20000 || System.currentTimeMillis() > until) { cut = true; break; }
            }
        }
        return (hits.isEmpty() ? "(no matches)" : TextUtils.join("\n", hits)) + (cut ? "\n[search was cut short; narrow the folder or pattern]" : "");
    }

    String search(String rel, String pattern, String globPattern, boolean ignoreCase) throws Exception {
        Pattern re;
        try { re = Pattern.compile(pattern, ignoreCase ? Pattern.CASE_INSENSITIVE : 0); }
        catch (Exception e) { throw new Exception("Invalid regular expression."); }
        Pattern nameRe = globPattern == null || globPattern.isEmpty() ? null : glob(globPattern);
        Node start = need(rel);
        List<String> out = new ArrayList<>();
        long until = System.currentTimeMillis() + 25_000;
        int visited = 0;
        boolean cut = false;
        ArrayDeque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{start, TextUtils.join("/", segments(rel))});
        outer:
        while (!stack.isEmpty()) {
            Object[] top = stack.pop();
            Node dir = (Node) top[0];
            String base = (String) top[1];
            for (Node n : children(dir.docId)) {
                if (n.name.equals(TRASH) || n.name.equals(BACKUP)) continue;
                String path = base.isEmpty() ? n.name : base + "/" + n.name;
                if (n.isDir()) { stack.push(new Object[]{n, path}); continue; }
                if (++visited > 20000 || System.currentTimeMillis() > until) { cut = true; break outer; }
                if (nameRe != null && !nameRe.matcher(n.name).matches()) continue;
                if (sensitive(path)) continue;
                String text;
                if (isOffice(n.name)) {
                    // Word / PowerPoint / Excel are searched too; PDFs are not (they need slow OCR on a phone)
                    if (n.size > 30L * 1024 * 1024) continue;
                    try { text = extractOffice(n)[0]; } catch (Exception e) { continue; }
                } else {
                    if (n.size > 2L * 1024 * 1024) continue;
                    byte[] b;
                    try { b = bytesOf(n, 2 * 1024 * 1024); } catch (Exception e) { continue; }
                    if (looksBinary(b)) continue;
                    text = decode(b);
                }
                String[] lines = text.split("\r?\n");
                for (int i = 0; i < lines.length; i++) {
                    if (re.matcher(lines[i]).find()) {
                        out.add(path + ":" + (i + 1) + ": " + (lines[i].length() > 200 ? lines[i].substring(0, 200) : lines[i]));
                        if (out.size() >= 200) { cut = true; break outer; }
                    }
                }
            }
        }
        return (out.isEmpty() ? "(no matches)" : TextUtils.join("\n", out)) + (cut ? "\n[results were cut short]" : "");
    }

    // ---------- Word / PowerPoint / Excel ----------

    static boolean isOffice(String name) { return ext(name).matches("docx|pptx|xlsx"); }

    /** Opens a PDF for rendering (pages are then read with on-device OCR) */
    android.os.ParcelFileDescriptor openPdf(String rel) throws Exception {
        Node n = need(rel);
        if (n.isDir()) throw new Exception("This is a folder.");
        if (n.size > 100L * 1024 * 1024) throw new Exception("The file is too large (over 100 MB).");
        android.os.ParcelFileDescriptor fd = cr.openFileDescriptor(docUri(n.docId), "r");
        if (fd == null) throw new Exception("Could not open the file.");
        return fd;
    }

    private static java.util.Map<String, byte[]> zipParts(InputStream in, Pattern want) throws Exception {
        java.util.Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!want.matcher(e.getName()).matches()) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = z.read(buf)) > 0) {
                    total += n;
                    if (total > 60L * 1024 * 1024) throw new Exception("A part of this file expands to an unusually large size, so it was not read.");
                    bo.write(buf, 0, n);
                }
                out.put(e.getName(), bo.toByteArray());
            }
        }
        return out;
    }

    private static String entities(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String tidy(String s) {
        return s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static int colIndex(String letters) {
        int n = 0;
        for (char ch : letters.toCharArray()) n = n * 26 + (ch - 'A' + 1);
        return n - 1;
    }

    String readOffice(String rel, int offset) throws Exception {
        Node n = need(rel);
        if (n.isDir()) throw new Exception("This is a folder.");
        String[] r = extractOffice(n);
        String text = r[0], note = r[1];
        int start = Math.max(0, Math.min(offset, text.length()));
        int end = Math.min(text.length(), start + MAX_READ_CHARS);
        String part = text.substring(start, end);
        if (end < text.length()) part += "\n[… " + (text.length() - end) + " more characters. Call again with offset=" + end + ".]";
        return (start == 0 ? "[" + note + "]\n" : "") + part;
    }

    /** Returns {text, note} for a Word / PowerPoint / Excel file */
    private String[] extractOffice(Node n) throws Exception {
        if (n.size > 100L * 1024 * 1024) throw new Exception("The file is too large (over 100 MB).");
        String ext = ext(n.name);
        InputStream in = cr.openInputStream(docUri(n.docId));
        if (in == null) throw new Exception("Could not open the file.");
        String text, note;
        if (ext.equals("docx")) {
            java.util.Map<String, byte[]> parts = zipParts(in, Pattern.compile("word/document\\.xml"));
            byte[] xml = parts.get("word/document.xml");
            if (xml == null) throw new Exception("No document body found in the Word file.");
            text = tidy(entities(new String(xml, StandardCharsets.UTF_8)
                    .replaceAll("<w:tab\\b[^>]*/>", "\t").replaceAll("<w:br\\b[^>]*/>", "\n").replace("</w:tc>", "\t")
                    .replace("</w:tr>", "\n").replace("</w:p>", "\n").replaceAll("<[^>]+>", "")));
            note = "Word document";
        } else if (ext.equals("pptx")) {
            java.util.Map<String, byte[]> parts = zipParts(in, Pattern.compile("ppt/slides/slide\\d+\\.xml"));
            List<String> names = new ArrayList<>(parts.keySet());
            Collections.sort(names, Comparator.comparingInt((String s) -> Integer.parseInt(s.replaceAll("\\D", ""))));
            if (names.isEmpty()) throw new Exception("No slides found in the PowerPoint file.");
            StringBuilder sb = new StringBuilder();
            for (String name : names) {
                String xml = new String(parts.get(name), StandardCharsets.UTF_8);
                sb.append("--- Slide ").append(name.replaceAll("\\D", "")).append(" ---\n")
                        .append(tidy(entities(xml.replace("</a:p>", "\n").replaceAll("<a:br\\b[^>]*/>", "\n").replaceAll("<[^>]+>", "")))).append("\n\n");
            }
            text = sb.toString().trim();
            note = "PowerPoint, " + names.size() + " slides";
        } else {
            java.util.Map<String, byte[]> parts = zipParts(in, Pattern.compile("xl/(sharedStrings|workbook)\\.xml|xl/worksheets/sheet\\d+\\.xml"));
            List<String> shared = new ArrayList<>();
            byte[] ss = parts.get("xl/sharedStrings.xml");
            if (ss != null) {
                java.util.regex.Matcher m = Pattern.compile("<si\\b[^>]*>([\\s\\S]*?)</si>").matcher(new String(ss, StandardCharsets.UTF_8));
                while (m.find()) {
                    StringBuilder one = new StringBuilder();
                    java.util.regex.Matcher t = Pattern.compile("<t\\b[^>]*>([\\s\\S]*?)</t>").matcher(m.group(1));
                    while (t.find()) one.append(t.group(1));
                    shared.add(entities(one.toString()));
                }
            }
            List<String> sheetNames = new ArrayList<>();
            byte[] wb = parts.get("xl/workbook.xml");
            if (wb != null) {
                java.util.regex.Matcher m = Pattern.compile("<sheet\\b[^>]*\\bname=\"([^\"]*)\"").matcher(new String(wb, StandardCharsets.UTF_8));
                while (m.find()) sheetNames.add(entities(m.group(1)));
            }
            List<String> sheets = new ArrayList<>();
            for (String k : parts.keySet()) if (k.startsWith("xl/worksheets/")) sheets.add(k);
            Collections.sort(sheets, Comparator.comparingInt((String s) -> Integer.parseInt(s.replaceAll("\\D", ""))));
            if (sheets.isEmpty()) throw new Exception("No sheets found in the Excel file.");
            StringBuilder sb = new StringBuilder();
            for (int si = 0; si < sheets.size(); si++) {
                String xml = new String(parts.get(sheets.get(si)), StandardCharsets.UTF_8);
                sb.append("--- Sheet: ").append(si < sheetNames.size() ? sheetNames.get(si) : "Sheet" + (si + 1)).append(" ---\n");
                int rows = 0;
                java.util.regex.Matcher r = Pattern.compile("<row\\b[^>]*>([\\s\\S]*?)</row>").matcher(xml);
                while (r.find() && rows < 5000) {
                    java.util.TreeMap<Integer, String> cells = new java.util.TreeMap<>();
                    java.util.regex.Matcher c = Pattern.compile("<c\\b([^>]*?)(?:/>|>([\\s\\S]*?)</c>)").matcher(r.group(1));
                    while (c.find()) {
                        java.util.regex.Matcher ref = Pattern.compile("\\br=\"([A-Z]+)\\d+\"").matcher(c.group(1));
                        if (!ref.find()) continue;
                        java.util.regex.Matcher ty = Pattern.compile("\\bt=\"([^\"]*)\"").matcher(c.group(1));
                        String type = ty.find() ? ty.group(1) : "";
                        String body = c.group(2) == null ? "" : c.group(2);
                        java.util.regex.Matcher vm = Pattern.compile("<v>([\\s\\S]*?)</v>").matcher(body);
                        String v = vm.find() ? vm.group(1) : "";
                        if (type.equals("s")) { int idx = v.isEmpty() ? -1 : Integer.parseInt(v.trim()); v = idx >= 0 && idx < shared.size() ? shared.get(idx) : ""; }
                        else if (type.equals("inlineStr")) {
                            StringBuilder one = new StringBuilder();
                            java.util.regex.Matcher t = Pattern.compile("<t\\b[^>]*>([\\s\\S]*?)</t>").matcher(body);
                            while (t.find()) one.append(t.group(1));
                            v = entities(one.toString());
                        } else v = entities(v);
                        if (!v.isEmpty()) cells.put(colIndex(ref.group(1)), v.replaceAll("[\\t\\r\\n]+", " "));
                    }
                    if (cells.isEmpty()) continue;
                    StringBuilder line = new StringBuilder();
                    int last = cells.lastKey();
                    for (int ci = 0; ci <= last; ci++) { if (ci > 0) line.append('\t'); String v = cells.get(ci); if (v != null) line.append(v); }
                    sb.append(line).append('\n');
                    rows++;
                }
                sb.append('\n');
            }
            text = sb.toString().trim();
            note = "Excel, " + sheets.size() + " sheet" + (sheets.size() > 1 ? "s" : "");
        }
        if (text.isEmpty()) throw new Exception("No text could be found in this file.");
        return new String[]{text, note};
    }
}
