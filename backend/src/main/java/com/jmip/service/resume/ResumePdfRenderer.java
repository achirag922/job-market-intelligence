package com.jmip.service.resume;

import com.jmip.dto.resume.BuilderContent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * V9.4: a built resume as a PDF, drawn with PDFBox (already used to read uploads), in one of two
 * plain, ATS-friendly templates: selectable text in one column, standard fonts, no images or tables.
 * Each entry (a job, a degree, a project) is kept on one page when it fits, and a heading never ends
 * a page on its own. Only the user's content is printed: no ids, dates of export or product names.
 */
@Component
public class ResumePdfRenderer {

    private static final float MARGIN = 54;
    private static final PDRectangle PAGE = PDRectangle.A4;

    /** What differs between the templates. */
    private record Style(PDFont regular, PDFont bold, PDFont italic, Color accent, boolean centeredHeader,
                         boolean headingRule, float nameSize, boolean uppercaseHeadings) {
    }

    private static final Style CLASSIC = new Style(font(Standard14Fonts.FontName.TIMES_ROMAN),
            font(Standard14Fonts.FontName.TIMES_BOLD), font(Standard14Fonts.FontName.TIMES_ITALIC), Color.BLACK,
            true, true, 20, true);
    private static final Style MODERN = new Style(font(Standard14Fonts.FontName.HELVETICA),
            font(Standard14Fonts.FontName.HELVETICA_BOLD), font(Standard14Fonts.FontName.HELVETICA_OBLIQUE),
            new Color(31, 78, 140), false, false, 22, false);

    private static PDFont font(Standard14Fonts.FontName name) {
        return new PDType1Font(name);
    }

    public byte[] render(BuilderContent content) {
        Style style = "MODERN".equals(content.template()) ? MODERN : CLASSIC;
        try (PDDocument document = new PDDocument()) {
            Writer writer = new Writer(document, style);
            header(writer, style, content.personal());
            if (content.summary() != null) {
                writer.section("Summary", List.of(writer.paragraph(content.summary(), style.regular(), 10.5f, 0)));
            }
            if (!content.skills().isEmpty()) {
                writer.section("Skills", List.of(writer.paragraph(String.join(" · ", content.skills()), style.regular(), 10.5f, 0)));
            }
            if (!content.experience().isEmpty()) {
                writer.section("Experience", content.experience().stream().map(job -> writer.entry(job.title(),
                        ResumeDocument.join(" · ", job.company(), job.location()),
                        ResumeDocument.dates(job.start(), job.end(), job.current()), null, job.bullets())).toList());
            }
            if (!content.education().isEmpty()) {
                writer.section("Education", content.education().stream().map(school -> writer.entry(school.degree(),
                        ResumeDocument.join(" · ", school.institution(), school.location()),
                        ResumeDocument.dates(school.start(), school.end(), false), school.details(), List.of())).toList());
            }
            if (!content.projects().isEmpty()) {
                writer.section("Projects", content.projects().stream().map(project -> writer.entry(project.name(),
                        project.url(), null, project.description(), project.bullets())).toList());
            }
            if (!content.certifications().isEmpty()) {
                writer.section("Certifications", content.certifications().stream().map(cert -> writer.entry(cert.name(),
                        cert.issuer(), cert.date(), null, List.of())).toList());
            }
            if (!content.achievements().isEmpty()) {
                writer.section("Achievements", List.of(writer.bullets(content.achievements())));
            }
            for (BuilderContent.Section extra : content.additional()) {
                writer.section(extra.title(), List.of(writer.bullets(extra.items())));
            }
            writer.close();
            // The document's own title is the person's name; nothing about JMIP or the stored resume.
            document.getDocumentInformation().setTitle(writer.clean(content.personal().fullName(), style.regular()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not render the resume", exception);
        }
    }

    private static void header(Writer writer, Style style, BuilderContent.Personal personal) throws IOException {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(personal.fullName(), style.bold(), style.nameSize(), 0, style.accent(), null, style.centeredHeader(), 4));
        if (personal.headline() != null) {
            lines.add(new Line(personal.headline(), style.regular(), 11.5f, 0, Color.DARK_GRAY, null, style.centeredHeader(), 2));
        }
        String contact = ResumeDocument.join("  |  ", personal.email(), personal.phone(), personal.location());
        if (contact != null) {
            lines.add(new Line(contact, style.regular(), 9.5f, 0, Color.DARK_GRAY, null, style.centeredHeader(), 1));
        }
        for (String link : personal.links()) {
            lines.add(new Line(link, style.regular(), 9.5f, 0, Color.DARK_GRAY, null, style.centeredHeader(), 1));
        }
        writer.block(new Block(lines, 8));
    }

    // ------------------------------------------------------------------ layout

    /** One line of text; {@code right} is drawn right-aligned on the same baseline (dates). */
    private record Line(String text, PDFont font, float size, float indent, Color color, String right, boolean centered,
                        float spaceBefore) {
        float height() {
            return size * 1.25f + spaceBefore;
        }
    }

    /** Lines kept together on one page when they fit. */
    private record Block(List<Line> lines, float spaceAfter) {
        float height() {
            return (float) lines.stream().mapToDouble(Line::height).sum() + spaceAfter;
        }
    }

    private static final class Writer {
        private final PDDocument document;
        private final Style style;
        private final float width = PAGE.getWidth() - 2 * MARGIN;
        private PDPageContentStream stream;
        private float y;

        Writer(PDDocument document, Style style) throws IOException {
            this.document = document;
            this.style = style;
            newPage();
        }

        void section(String heading, List<Block> entries) throws IOException {
            if (entries.isEmpty()) {
                return;
            }
            String title = style.uppercaseHeadings() ? heading.toUpperCase(Locale.ROOT) : heading;
            Block head = new Block(List.of(new Line(title, style.bold(), style.uppercaseHeadings() ? 11 : 12.5f, 0,
                    style.accent(), null, false, 8)), style.headingRule() ? 6 : 3);
            // A heading never ends a page on its own: it moves with its first entry.
            if (head.height() + Math.min(entries.get(0).height(), usable()) > room()) {
                newPage();
            }
            block(head);
            if (style.headingRule()) {
                stream.setStrokingColor(Color.GRAY);
                stream.setLineWidth(0.6f);
                stream.moveTo(MARGIN, y + 4);
                stream.lineTo(MARGIN + width, y + 4);
                stream.stroke();
            }
            for (Block entry : entries) {
                block(entry);
            }
        }

        Block entry(String title, String subtitle, String dates, String text, List<String> bullets) {
            List<Line> lines = new ArrayList<>();
            lines.add(new Line(title, style.bold(), 10.5f, 0, Color.BLACK, dates, false, 2));
            if (subtitle != null) {
                lines.add(new Line(subtitle, style.italic(), 10, 0, Color.DARK_GRAY, null, false, 0));
            }
            if (text != null) {
                lines.addAll(wrap(text, style.regular(), 10, 0));
            }
            for (String bullet : bullets) {
                List<Line> wrapped = wrap(bullet, style.regular(), 10, 12);
                lines.add(new Line("•", style.regular(), 10, 3, Color.BLACK, null, false, 0));
                // The bullet shares the first wrapped line's baseline.
                Line first = wrapped.get(0);
                lines.set(lines.size() - 1, new Line("•  " + first.text(), first.font(), first.size(), 3, first.color(),
                        null, false, 1));
                lines.addAll(wrapped.subList(1, wrapped.size()));
            }
            return new Block(lines, 5);
        }

        Block paragraph(String text, PDFont font, float size, float indent) {
            return new Block(wrap(text, font, size, indent), 3);
        }

        Block bullets(List<String> items) {
            List<Line> lines = new ArrayList<>();
            for (String item : items) {
                List<Line> wrapped = wrap(item, style.regular(), 10, 12);
                Line first = wrapped.get(0);
                lines.add(new Line("•  " + first.text(), first.font(), first.size(), 3, first.color(), null, false, 1));
                lines.addAll(wrapped.subList(1, wrapped.size()));
            }
            return new Block(lines, 3);
        }

        /** Draws a block, starting a new page first when it fits on a fresh one but not here. */
        void block(Block block) throws IOException {
            if (block.height() > room() && block.height() <= usable()) {
                newPage();
            }
            for (Line line : block.lines()) {
                if (line.height() > room()) {
                    newPage();
                }
                y -= line.height();
                draw(line);
            }
            y -= block.spaceAfter();
        }

        private void draw(Line line) throws IOException {
            String text = clean(line.text(), line.font());
            float textWidth = line.font().getStringWidth(text) / 1000 * line.size();
            float x = line.centered() ? MARGIN + (width - textWidth) / 2 : MARGIN + line.indent();
            write(text, line.font(), line.size(), x, line.color());
            if (line.right() != null) {
                String right = clean(line.right(), style.regular());
                float rightWidth = style.regular().getStringWidth(right) / 1000 * 9.5f;
                write(right, style.regular(), 9.5f, MARGIN + width - rightWidth, Color.DARK_GRAY);
            }
        }

        private void write(String text, PDFont font, float size, float x, Color color) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, y);
            stream.showText(text);
            stream.endText();
        }

        /** Word-wraps to the column; a word longer than the line is kept whole on its own line. */
        List<Line> wrap(String text, PDFont font, float size, float indent) {
            List<Line> lines = new ArrayList<>();
            for (String paragraph : clean(text, font).split("\n")) {
                StringBuilder current = new StringBuilder();
                for (String word : paragraph.split(" +")) {
                    String candidate = current.isEmpty() ? word : current + " " + word;
                    if (!current.isEmpty() && widthOf(candidate, font, size) > width - indent - (indent > 0 ? 6 : 0)) {
                        lines.add(new Line(current.toString(), font, size, indent, Color.BLACK, null, false, 0));
                        current = new StringBuilder(word);
                    } else {
                        current = new StringBuilder(candidate);
                    }
                }
                lines.add(new Line(current.toString(), font, size, indent, Color.BLACK, null, false, 0));
            }
            return lines;
        }

        /** Keeps what the standard fonts can print; anything else becomes "?", never a failure. */
        String clean(String text, PDFont font) {
            StringBuilder out = new StringBuilder();
            text.replace('\t', ' ').replace("\r", "").codePoints().forEach(cp -> {
                String ch = new String(Character.toChars(cp));
                if (cp == '\n') {
                    out.append(ch);
                    return;
                }
                if (Character.isISOControl(cp)) {
                    return;
                }
                try {
                    font.encode(ch);
                    out.append(ch);
                } catch (IOException | IllegalArgumentException unsupported) {
                    out.append('?');
                }
            });
            return out.toString();
        }

        private float widthOf(String text, PDFont font, float size) {
            try {
                return font.getStringWidth(text) / 1000 * size;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private float room() {
            return y - MARGIN;
        }

        private float usable() {
            return PAGE.getHeight() - 2 * MARGIN;
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PAGE);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PAGE.getHeight() - MARGIN;
        }

        void close() throws IOException {
            stream.close();
        }
    }
}
