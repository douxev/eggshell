import SwiftUI
import TransitionCore
import PhotosUI
import UIKit

// Photos (§6.10) — a pushed screen of the one stack.
//
// Two segments: Galerie shows the library, Comparer puts the oldest and the
// newest side by side and says out loud how much time separates them. That
// sentence is the whole point of the screen: a transition is slow enough that
// you stop seeing it in the mirror, and a caption doing the arithmetic for you
// is what makes it visible again.
//
// iOS grammar (§4): no FAB — the main action lives in the anchored bottom bar,
// which reserves its 84 pt band. Nothing here is a shadowed floating surface.

// MARK: - ViewModel

@MainActor
final class PhotosViewModel: ObservableObject {
    @Published var loading = true
    @Published var records: [PhotoRecord] = []
    @Published var error: String?
    /// Bumped on a successful import so the view can fire a success haptic.
    @Published var savedTick = 0

    /// Loads only the photo *records* (metadata) — never the blobs. Each
    /// thumbnail decrypts itself on demand so a large library doesn't pin
    /// every decrypted image in memory at once.
    func load(_ session: VaultService) async {
        loading = true
        error = nil
        do {
            let recs = try await session.listPhotoRecords()
            records = recs.sorted { $0.atMs > $1.atMs }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    /// Decrypt a single record's blob to plaintext bytes (on demand).
    func decrypt(_ rec: PhotoRecord, session: VaultService) async -> Data? {
        do {
            return try await session.decryptBlobFile(URL(fileURLWithPath: rec.filePath))
        } catch {
            return nil
        }
    }

    /// Strip EXIF (GPS / camera model / timestamps) by re-encoding the image
    /// to a metadata-free JPEG, then encrypt the ciphertext to disk and insert
    /// the DB row.
    func importImage(_ image: UIImage, session: VaultService) async {
        guard let cleaned = image.jpegData(compressionQuality: 0.92) else {
            self.error = "On n'a pas réussi à lire cette image. Essaie-en une autre."
            return
        }
        do {
            let url = try await session.encryptBlobToFile(cleaned, in: AppPaths.photosDir)
            _ = try await session.addPhotoRecord(NewPhotoRecord(
                atMs: Time.nowMs(),
                category: nil,
                filePath: url.path,
                notes: nil))
            savedTick += 1
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }

    func delete(_ rec: PhotoRecord, session: VaultService) async {
        do {
            try await session.deletePhotoRecord(rec.id)
            AppPaths.secureDelete(URL(fileURLWithPath: rec.filePath))
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }
}

// MARK: - Screen

struct PhotosView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = PhotosViewModel()

    /// 0 = Galerie, 1 = Comparer. `SegmentedSelector` binds an index.
    @State private var tab = 0
    @State private var pickerItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var openedId: Int64?

    private let columns = [GridItem(.flexible(), spacing: 10),
                           GridItem(.flexible(), spacing: 10)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SegmentedSelector(
                    options: ["Galerie", "Comparer"],
                    selection: $tab,
                    accessibilityLabel: "Affichage des photos")
                    .frame(maxWidth: .infinity)

                if let message = vm.error {
                    ErrorCardView(message, retryLabel: "Réessayer") { Task { await reload() } }
                }

                if vm.loading {
                    loadingSkeleton
                } else if tab == 0 {
                    gallery
                } else {
                    comparison
                }

                privacyNote
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.s)
            .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Photos")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { dismiss() } label: {
                    HStack(spacing: 2) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 16, weight: .semibold))
                        Text("Retour").font(.system(size: 16))
                    }
                    .foregroundStyle(palette.primary)
                }
                .accessibilityLabel("Retour")
            }
        }
        .toolbarBackground(palette.surface, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .eggActionBar {
            // The main action has two sources, so the bar hosts a Menu wearing
            // the geometry of `ActionBarButton` rather than the button itself.
            Menu {
                Button { showCamera = true } label: {
                    Label("Prendre une photo", systemImage: "camera")
                }
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Label("Choisir dans la photothèque", systemImage: "photo.on.rectangle")
                }
            } label: {
                addPhotoLabel
            }
            .accessibilityLabel("Ajouter une photo")
        }
        .fullScreenCover(isPresented: Binding(
            get: { openedId != nil },
            set: { if !$0 { openedId = nil } }
        )) {
            if let id = openedId, let rec = vm.records.first(where: { $0.id == id }) {
                PhotoLightbox(record: rec, vm: vm) { openedId = nil }
            }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { image in
                showCamera = false
                guard let image, let session = app.session else { return }
                Task { await vm.importImage(image, session: session) }
            }
            .ignoresSafeArea()
        }
        .onChange(of: pickerItem) { _, newItem in
            guard let newItem else { return }
            Task {
                if let data = try? await newItem.loadTransferable(type: Data.self),
                   let image = UIImage(data: data),
                   let session = app.session {
                    await vm.importImage(image, session: session)
                }
                pickerItem = nil
            }
        }
        .sensoryFeedback(.success, trigger: vm.savedTick)
        .task { await reload() }
    }

    private func reload() async {
        if let session = app.session { await vm.load(session) }
    }

    // MARK: Action-bar label

    private var addPhotoLabel: some View {
        HStack(spacing: Spacing.s) {
            Image(systemName: "camera.fill").font(.system(size: 17, weight: .semibold))
            Text("Ajouter une photo").font(.system(size: 15.5, weight: .semibold))
        }
        .foregroundStyle(palette.onPrimary)
        .frame(maxWidth: .infinity)
        .frame(height: 46)
        .background(palette.primary, in: Capsule())
        .contentShape(Capsule())
    }

    // MARK: Loading — skeletons at the real tiles' dimensions, never a spinner

    private var loadingSkeleton: some View {
        LazyVGrid(columns: columns, spacing: 14) {
            ForEach(0..<4, id: \.self) { _ in
                SkeletonBlock(height: 190, cornerRadius: Radius.launcherTile)
            }
        }
    }

    // MARK: Galerie

    @ViewBuilder
    private var gallery: some View {
        if vm.records.isEmpty {
            EmptyStateView(
                "Aucune photo pour l'instant. La première n'a rien à prouver : "
                    + "c'est juste le point de départ auquel tu compareras les suivantes.",
                systemImage: "photo.on.rectangle.angled",
                actionLabel: "Prendre une première photo") { showCamera = true }
        } else {
            VStack(alignment: .leading, spacing: 10) {
                MicroLabel(galleryEyebrow)
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(vm.records, id: \.id) { rec in
                        Button { openedId = rec.id } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                photoTile(rec)
                                Text(shortDate(rec.atMs))
                                    .font(EggFont.bodyS)
                                    .foregroundStyle(palette.onSurfaceVariant)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Photo du \(longDate(rec.atMs))")
                        .accessibilityHint("Ouvre la visionneuse")
                    }
                }
            }
        }
    }

    private var galleryEyebrow: String {
        let count = vm.records.count
        let noun = count <= 1 ? "CLICHÉ" : "CLICHÉS"
        guard let oldest = vm.records.last else { return "\(count) \(noun)" }
        return "\(count) \(noun) · DEPUIS \(monthLabel(oldest.atMs))"
    }

    // MARK: Comparer — le plus ancien face au plus récent

    @ViewBuilder
    private var comparison: some View {
        let newest = vm.records.first
        let oldest = vm.records.last

        if let oldest, let newest, oldest.id != newest.id {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 10) {
                    comparePane(
                        oldest,
                        caption: "AVANT · \(monthLabel(oldest.atMs))",
                        tint: palette.onSurfaceVariant)
                    comparePane(
                        newest,
                        caption: "MAINTENANT · \(monthLabel(newest.atMs))",
                        tint: palette.primary)
                }

                EggCard(variant: .primary, spacing: 2) {
                    Text(spanTitle(from: oldest.atMs, to: newest.atMs))
                        .font(.system(size: 22, weight: .semibold))
                    Text("entre ces deux photos · \(vm.records.count) "
                         + (vm.records.count <= 1 ? "cliché" : "clichés") + " au total")
                        .font(EggFont.bodyS)
                        .opacity(0.8)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
            }
        } else {
            EmptyStateView(
                "Il faut deux photos pour voir le chemin parcouru. "
                    + "Ajoute-en une deuxième et la comparaison s'affichera ici toute seule.",
                systemImage: "rectangle.on.rectangle.angled",
                actionLabel: "Prendre une photo") { showCamera = true }
        }
    }

    private func comparePane(_ rec: PhotoRecord, caption: String, tint: Color) -> some View {
        Button { openedId = rec.id } label: {
            VStack(spacing: Spacing.s) {
                photoTile(rec)
                Text(caption)
                    .font(EggFont.micro)
                    .tracking(0.5)
                    .foregroundStyle(tint)
                    .multilineTextAlignment(.center)
            }
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
        .accessibilityLabel("\(caption.lowercased()), photo du \(longDate(rec.atMs))")
        .accessibilityHint("Ouvre la visionneuse")
    }

    private func photoTile(_ rec: PhotoRecord) -> some View {
        PhotoThumbnail(record: rec, vm: vm)
            .frame(maxWidth: .infinity)
            .aspectRatio(3.0 / 4.0, contentMode: .fill)
            .clipShape(RoundedRectangle(cornerRadius: Radius.launcherTile, style: .continuous))
    }

    // MARK: Encart de confidentialité

    /// iOS cannot block the screenshot gesture the way `FLAG_SECURE` does on
    /// Android, so this says what the app actually guarantees here — the vault
    /// and the shield — instead of promising something the platform won't keep.
    private var privacyNote: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "eye.slash")
                .font(.system(size: 19))
                .foregroundStyle(palette.primary)
            Text("Tes photos vivent dans le coffre chiffré, jamais dans la galerie du téléphone. "
                 + "L'app se masque dès qu'elle passe en arrière-plan ou qu'un enregistrement "
                 + "d'écran démarre.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, Metrics.screenMargin)
        .padding(.vertical, 14)
        .background(
            palette.surfaceContainer,
            in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Visionneuse

/// Full screen, pinch-zoom, pan, double-tap, and the three actions of §6.10.
/// Deleting is destructive, so it goes through an alert (§5.4).
private struct PhotoLightbox: View {
    let record: PhotoRecord
    @ObservedObject var vm: PhotosViewModel
    let onClose: () -> Void

    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette

    @State private var image: UIImage?
    @State private var shareTarget: PhotoShareTarget?
    @State private var preparingShare = false
    @State private var confirmDelete = false
    @State private var toast: String?

    var body: some View {
        ZStack {
            // A viewer is a dark room in every one of the 14 palettes: the
            // photograph sets the brightness, so the backdrop is the scrim
            // token and the chrome above it stays light enough to read over
            // any picture.
            palette.scrim.ignoresSafeArea()

            if let image {
                ZoomablePhoto(image: image)
            } else {
                ProgressView().tint(.white)
            }

            VStack(spacing: 0) {
                topBar
                Spacer(minLength: 0)
                if let toast {
                    Text(toast)
                        .font(EggFont.label)
                        .foregroundStyle(.white)
                        .padding(.horizontal, Metrics.cardPadding)
                        .padding(.vertical, Spacing.m)
                        .background(palette.scrim.opacity(0.55), in: Capsule())
                        .padding(.bottom, Spacing.m)
                        .transition(.opacity)
                }
                actionRow
            }
        }
        .task { await loadImage() }
        .sheet(item: $shareTarget) { target in
            PhotoShareSheet(url: target.url) { AppPaths.secureDelete(target.url) }
        }
        .alert("Supprimer cette photo ?", isPresented: $confirmDelete) {
            Button("Annuler", role: .cancel) {}
            Button("Supprimer", role: .destructive) { deleteNow() }
        } message: {
            Text("Elle quitte le coffre pour de bon. On ne pourra pas la récupérer.")
        }
    }

    private var topBar: some View {
        HStack(alignment: .center) {
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: Metrics.touchTarget, height: Metrics.touchTarget)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Fermer la visionneuse")
            Spacer(minLength: Spacing.s)
            Text(longDate(record.atMs))
                .font(EggFont.bodyS)
                .foregroundStyle(.white)
                .padding(.trailing, Spacing.m)
        }
        .padding(.horizontal, Spacing.s)
    }

    private var actionRow: some View {
        HStack(spacing: 0) {
            Button { startShare() } label: {
                actionLabel(preparingShare ? "Préparation…" : "Partager", "square.and.arrow.up")
            }
            .disabled(preparingShare || image == nil)

            Button { saveToCameraRoll() } label: {
                actionLabel("Enregistrer", "square.and.arrow.down")
            }
            .disabled(image == nil)

            Button { confirmDelete = true } label: {
                actionLabel("Supprimer", "trash")
            }
        }
        .padding(.horizontal, Metrics.screenMargin)
        .padding(.top, Spacing.m)
        .padding(.bottom, Spacing.xl)
    }

    private func actionLabel(_ text: String, _ symbol: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: symbol).font(.system(size: 19, weight: .semibold))
            Text(text).font(EggFont.micro).tracking(0.5)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity)
        .frame(minHeight: Metrics.touchTarget)
        .contentShape(Rectangle())
    }

    private func loadImage() async {
        guard let session = app.session else { return }
        if let data = await vm.decrypt(record, session: session) {
            image = UIImage(data: data)
        }
    }

    /// One tap: decrypt to a temporary plaintext file, then hand it to a sheet
    /// that owns the clean-up. The plaintext never outlives the sheet.
    private func startShare() {
        guard let session = app.session else { return }
        preparingShare = true
        Task {
            if let data = await vm.decrypt(record, session: session) {
                let url = AppPaths.cacheDir
                    .appendingPathComponent("photo-\(record.id)-\(Time.nowMs()).jpg")
                try? data.write(to: url, options: .completeFileProtection)
                shareTarget = PhotoShareTarget(url: url)
            }
            preparingShare = false
        }
    }

    private func saveToCameraRoll() {
        guard let image else { return }
        UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
        show("Copiée dans la pellicule du téléphone")
    }

    private func show(_ message: String) {
        withAnimation { toast = message }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            withAnimation { toast = nil }
        }
    }

    private func deleteNow() {
        guard let session = app.session else { return }
        Task {
            await vm.delete(record, session: session)
            onClose()
        }
    }
}

/// Identifiable wrapper so `.sheet(item:)` can carry a plaintext URL.
private struct PhotoShareTarget: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

/// The share step lives in its own sheet so the decrypted copy can be wiped the
/// moment the sheet closes — defence in depth, no plaintext left lying around.
private struct PhotoShareSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    let url: URL
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                Text("Une copie en clair a été préparée juste pour ce partage. "
                     + "Elle est effacée dès que tu fermes cette feuille.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                ShareLink(item: url) {
                    HStack(spacing: Spacing.s) {
                        Image(systemName: "square.and.arrow.up")
                            .font(.system(size: 17, weight: .semibold))
                        Text("Partager la photo").font(.system(size: 15.5, weight: .semibold))
                    }
                    .foregroundStyle(palette.onPrimary)
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .background(palette.primary, in: Capsule())
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)

                Spacer(minLength: 0)
            }
            .padding(Metrics.cardPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surface.ignoresSafeArea())
            .navigationTitle("Partager")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Fermer") { dismiss() }
                }
            }
        }
        .presentationDetents([.height(260)])
        .presentationDragIndicator(.visible)
        .onDisappear { onClose() }
    }
}

// MARK: - On-demand thumbnail

/// Decrypts its own blob inside a `.task` keyed to the record id. The decrypted
/// bytes stay scoped to this cell so scrolling a large grid never loads the
/// whole library into memory at once.
private struct PhotoThumbnail: View {
    let record: PhotoRecord
    @ObservedObject var vm: PhotosViewModel

    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                ZStack {
                    palette.surfaceContainerHigh
                    Image(systemName: "photo")
                        .font(.system(size: 24))
                        .foregroundStyle(palette.outline)
                }
            }
        }
        .clipped()
        .task(id: record.id) {
            guard let session = app.session else { return }
            if let data = await vm.decrypt(record, session: session) {
                image = UIImage(data: data)
            }
        }
    }
}

// MARK: - Zoomable image

private struct ZoomablePhoto: View {
    let image: UIImage

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .scaleEffect(scale)
            .offset(offset)
            .gesture(
                MagnificationGesture()
                    .onChanged { value in scale = min(6, max(1, lastScale * value)) }
                    .onEnded { _ in
                        lastScale = scale
                        if scale <= 1 { reset() }
                    }
                    .simultaneously(with:
                        DragGesture()
                            .onChanged { value in
                                // Panning only means something once zoomed in.
                                guard scale > 1 else { return }
                                offset = CGSize(
                                    width: lastOffset.width + value.translation.width,
                                    height: lastOffset.height + value.translation.height)
                            }
                            .onEnded { _ in lastOffset = offset }
                    )
            )
            .onTapGesture(count: 2) {
                withAnimation(.easeOut(duration: 0.2)) {
                    if scale > 1 {
                        reset()
                    } else {
                        scale = 2.5
                        lastScale = 2.5
                    }
                }
            }
            .accessibilityLabel("Photo en plein écran")
            .accessibilityHint("Pince pour zoomer, double-tape pour revenir à la taille d'origine")
    }

    private func reset() {
        scale = 1
        lastScale = 1
        offset = .zero
        lastOffset = .zero
    }
}

// MARK: - Camera picker (UIKit bridge)

private struct CameraPicker: UIViewControllerRepresentable {
    let onResult: (UIImage?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onResult: onResult) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            picker.sourceType = .camera
        } else {
            picker.sourceType = .photoLibrary
        }
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onResult: (UIImage?) -> Void
        init(onResult: @escaping (UIImage?) -> Void) { self.onResult = onResult }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            onResult(info[.originalImage] as? UIImage)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onResult(nil)
        }
    }
}

// MARK: - Date helpers

private func shortDate(_ ms: Int64) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "fr_FR")
    formatter.dateFormat = "d MMM yyyy"
    return formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
}

private func longDate(_ ms: Int64) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "fr_FR")
    formatter.dateFormat = "EEEE d MMMM yyyy"
    return formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
}

/// « MARS 2025 » — the small-caps caption of the comparison, uppercased in the
/// string itself so a translation can opt out (§3.3).
private func monthLabel(_ ms: Int64) -> String {
    let locale = Locale(identifier: "fr_FR")
    let formatter = DateFormatter()
    formatter.locale = locale
    formatter.dateFormat = "MMM yyyy"
    return formatter
        .string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
        .uppercased(with: locale)
}

/// « 16 mois » — and below a month, something honest rather than a rounded 0.
private func spanTitle(from fromMs: Int64, to toMs: Int64) -> String {
    let calendar = Calendar.current
    let start = Date(timeIntervalSince1970: Double(fromMs) / 1000)
    let end = Date(timeIntervalSince1970: Double(toMs) / 1000)
    let months = calendar.dateComponents([.month], from: start, to: end).month ?? 0
    if months >= 1 { return "\(months) mois" }
    let days = max(0, calendar.dateComponents([.day], from: start, to: end).day ?? 0)
    if days >= 14 { return "\(days / 7) semaines" }
    return days <= 1 ? "\(days) jour" : "\(days) jours"
}
