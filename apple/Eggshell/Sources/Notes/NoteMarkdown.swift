import SwiftUI

// Markdown rendering, shared by the notes module and anything else that has to
// draw a note. Rendering only — no storage, no vocabulary from the app: this
// file must stay usable by a screen that is not supposed to look like eggshell.
//
// Mirrors android's ui/notes/NoteComposables.kt, and takes the same decision:
// images are NOT inlined in the markdown as base64. They are referenced by id
// and resolved by the caller, so a missing attachment degrades to a gap rather
// than to corrupt markup.

/// How an image reference is written inside a body. `eggshell-note-image:42`.
enum NoteImageRef {
    static let scheme = "eggshell-note-image"

    static func marker(for imageId: Int64) -> String { "![](\(scheme):\(imageId))" }

    /// The image id in `![](eggshell-note-image:42)`, or nil if the line is not
    /// one of our markers.
    static func imageId(inMarkerLine line: String) -> Int64? {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        let prefix = "![](\(scheme):"
        guard trimmed.hasPrefix(prefix), trimmed.hasSuffix(")") else { return nil }
        let inner = trimmed.dropFirst(prefix.count).dropLast()
        return Int64(inner)
    }
}

/// One drawable piece of a note body.
enum NoteBlock: Identifiable {
    case heading(level: Int, text: String, id: Int)
    case bullet(text: String, id: Int)
    case quote(text: String, id: Int)
    case paragraph(text: String, id: Int)
    case image(id: Int64, key: Int)
    case rule(id: Int)

    var id: Int {
        switch self {
        case .heading(_, _, let id), .bullet(_, let id), .quote(_, let id),
             .paragraph(_, let id), .rule(let id):
            return id
        case .image(_, let key):
            return key
        }
    }
}

/// Split a body into blocks.
///
/// Deliberately a small block-level pass rather than a full CommonMark parser:
/// inline emphasis is handed to `AttributedString(markdown:)`, which the system
/// already ships, and the block grammar here is the subset a note actually
/// uses. Consecutive non-empty lines join into one paragraph, the way markdown
/// expects — otherwise a wrapped sentence would draw as several.
func parseNoteBlocks(_ body: String) -> [NoteBlock] {
    var blocks: [NoteBlock] = []
    var paragraph: [String] = []
    var key = 0
    func nextKey() -> Int { key += 1; return key }

    func flushParagraph() {
        guard !paragraph.isEmpty else { return }
        blocks.append(.paragraph(text: paragraph.joined(separator: " "), id: nextKey()))
        paragraph.removeAll()
    }

    for rawLine in body.components(separatedBy: .newlines) {
        let line = rawLine.trimmingCharacters(in: .whitespaces)

        if let imageId = NoteImageRef.imageId(inMarkerLine: line) {
            flushParagraph()
            blocks.append(.image(id: imageId, key: nextKey()))
            continue
        }
        if line.isEmpty {
            flushParagraph()
            continue
        }
        if line == "---" || line == "***" || line == "___" {
            flushParagraph()
            blocks.append(.rule(id: nextKey()))
            continue
        }
        if line.hasPrefix("#") {
            let hashes = line.prefix(while: { $0 == "#" }).count
            if hashes <= 6, line.count > hashes, line[line.index(line.startIndex, offsetBy: hashes)] == " " {
                flushParagraph()
                let text = String(line.dropFirst(hashes)).trimmingCharacters(in: .whitespaces)
                blocks.append(.heading(level: hashes, text: text, id: nextKey()))
                continue
            }
        }
        if line.hasPrefix("- ") || line.hasPrefix("* ") || line.hasPrefix("+ ") {
            flushParagraph()
            blocks.append(.bullet(text: String(line.dropFirst(2)), id: nextKey()))
            continue
        }
        if line.hasPrefix("> ") {
            flushParagraph()
            blocks.append(.quote(text: String(line.dropFirst(2)), id: nextKey()))
            continue
        }
        paragraph.append(line)
    }
    flushParagraph()
    return blocks
}

/// Inline emphasis via the system parser, falling back to the raw text.
///
/// `.inlineOnlyPreservingWhitespace` keeps a line that merely *looks* like a
/// block construct from being swallowed — the block grammar above already
/// decided what this line is.
func noteInlineText(_ markdown: String) -> AttributedString {
    (try? AttributedString(
        markdown: markdown,
        options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
    )) ?? AttributedString(markdown)
}

/// Renders a parsed body. `image` is asked for a view per attachment id, so the
/// decrypting and caching stay with whoever owns the files.
struct NoteBodyView<ImageView: View>: View {
    @Environment(\.palette) private var palette
    /// Not named `body`: that name belongs to the View requirement below.
    let markdown: String
    let image: (Int64) -> ImageView

    init(_ markdown: String, @ViewBuilder image: @escaping (Int64) -> ImageView) {
        self.markdown = markdown
        self.image = image
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            ForEach(parseNoteBlocks(markdown)) { block in
                switch block {
                case .heading(let level, let text, _):
                    Text(noteInlineText(text))
                        .font(.system(size: level <= 1 ? 20 : (level == 2 ? 17 : 15),
                                      weight: .semibold))
                        .foregroundStyle(palette.onSurface)
                        .padding(.top, Spacing.xs)
                case .bullet(let text, _):
                    HStack(alignment: .top, spacing: Spacing.s) {
                        Text("•").foregroundStyle(palette.onSurfaceVariant)
                        Text(noteInlineText(text))
                            .font(.eggBody)
                            .foregroundStyle(palette.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                case .quote(let text, _):
                    HStack(alignment: .top, spacing: Spacing.s) {
                        Rectangle()
                            .fill(palette.outlineVariant)
                            .frame(width: 3)
                        Text(noteInlineText(text))
                            .font(.eggBody)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                case .paragraph(let text, _):
                    Text(noteInlineText(text))
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                case .image(let id, _):
                    image(id)
                case .rule:
                    Rectangle()
                        .fill(palette.outlineVariant)
                        .frame(height: 1)
                        .padding(.vertical, Spacing.xs)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The one-line summary a note card shows: markers and syntax stripped, so the
/// preview reads as prose rather than as source.
func noteSnippet(_ body: String, limit: Int = 140) -> String {
    var out: [String] = []
    for block in parseNoteBlocks(body) {
        switch block {
        case .heading(_, let text, _), .bullet(let text, _),
             .quote(let text, _), .paragraph(let text, _):
            out.append(String(noteInlineText(text).characters))
        case .image, .rule:
            continue
        }
        if out.joined(separator: " ").count >= limit { break }
    }
    let flat = out.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
    return flat.count > limit ? String(flat.prefix(limit)) + "…" : flat
}
