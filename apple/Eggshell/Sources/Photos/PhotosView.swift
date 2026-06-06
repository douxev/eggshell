import SwiftUI
import TransitionCore
import PhotosUI
import UIKit

// MARK: - ViewModel

@MainActor
final class PhotosViewModel: ObservableObject {
    @Published var loading = true
    @Published var records: [PhotoRecord] = []
    @Published var error: String?

    /// Loads only the photo *records* (metadata) — never the blobs. Each
    /// thumbnail decrypts itself on demand so a large library doesn't pin
    /// every decrypted image in memory at once.
    func load(_ session: VaultService) async {
        loading = true
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
            self.error = "Impossible de lire l'image."
            return
        }
        do {
            let url = try await session.encryptBlobToFile(cleaned, in: AppPaths.photosDir)
            _ = try await session.addPhotoRecord(NewPhotoRecord(
                atMs: Time.nowMs(),
                category: nil,
                filePath: url.path,
                notes: nil))
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

private enum PhotoTab: Hashable { case gallery, compare }

// MARK: - Root tab view

struct PhotosView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @StateObject private var vm = PhotosViewModel()

    @State private var tab: PhotoTab = .gallery
    @State private var pickerItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var selectedId: Int64?

    private let columns = [GridItem(.flexible(), spacing: Spacing.m),
                           GridItem(.flexible(), spacing: Spacing.m)]

    var body: some View {
        TabScaffold(title: "Photos") {
            HStack(spacing: Spacing.s) {
                ChoiceChip(label: "Galerie", selected: tab == .gallery) { tab = .gallery }
                ChoiceChip(label: "Comparaison", selected: tab == .compare) { tab = .compare }
            }

            if let e = vm.error { ErrorBanner(message: e) }

            if vm.loading {
                ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
            } else if vm.records.isEmpty {
                EmptyStateCard(text: "Aucune photo pour l'instant.", systemImage: "photo.on.rectangle")
            } else {
                switch tab {
                case .gallery: gallery
                case .compare: comparison
                }
            }
        }
        .overlay(alignment: .bottomTrailing) {
            Menu {
                Button {
                    showCamera = true
                } label: {
                    Label("Prendre une photo", systemImage: "camera")
                }
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Label("Choisir dans la photothèque", systemImage: "photo")
                }
            } label: {
                Image(systemName: "plus")
                    .font(.title2.weight(.semibold))
                    .frame(width: 60, height: 60)
            }
            .glassProminentButton().tint(palette.primary)
            .clipShape(Circle())
            .padding(Spacing.xl)
        }
        .fullScreenCover(isPresented: Binding(
            get: { selectedId != nil },
            set: { if !$0 { selectedId = nil } }
        )) {
            if let id = selectedId, let rec = vm.records.first(where: { $0.id == id }) {
                LightboxView(record: rec, vm: vm) { selectedId = nil }
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
        .task { if let s = app.session { await vm.load(s) } }
    }

    // MARK: Gallery

    private var gallery: some View {
        LazyVGrid(columns: columns, spacing: Spacing.l) {
            ForEach(vm.records, id: \.id) { rec in
                Button { selectedId = rec.id } label: {
                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        PhotoThumbnail(record: rec, vm: vm)
                            .frame(maxWidth: .infinity)
                            .aspectRatio(3.0 / 4.0, contentMode: .fill)
                            .clipShape(RoundedRectangle(cornerRadius: Corner.large, style: .continuous))
                        Text(shortDate(rec.atMs))
                            .font(.eggCaption)
                            .foregroundStyle(palette.onSurface.opacity(0.8))
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: Comparison (oldest vs newest)

    @ViewBuilder
    private var comparison: some View {
        // records are sorted newest-first; oldest is last, newest is first.
        let newest = vm.records.first
        let oldest = vm.records.last

        if let oldest, let newest, oldest.id != newest.id {
            VStack(spacing: Spacing.l) {
                HStack(alignment: .top, spacing: Spacing.m) {
                    comparePane(oldest, label: "Avant")
                    comparePane(newest, label: "Après")
                }
                SectionCard {
                    VStack(spacing: Spacing.xs) {
                        Text("\(monthsBetween(oldest.atMs, newest.atMs)) mois écoulés")
                            .font(.eggHeadline)
                            .foregroundStyle(palette.onPrimaryContainer)
                        Text("Entre la première et la dernière photo")
                            .font(.eggCaption)
                            .foregroundStyle(palette.onPrimaryContainer.opacity(0.85))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(Spacing.m)
                    .background(palette.primaryContainer)
                    .clipShape(RoundedRectangle(cornerRadius: Corner.medium, style: .continuous))
                }
            }
        } else {
            EmptyStateCard(
                text: "Ajoutez au moins deux photos pour les comparer.",
                systemImage: "rectangle.on.rectangle")
        }
    }

    private func comparePane(_ rec: PhotoRecord, label: String) -> some View {
        VStack(spacing: Spacing.xs) {
            PhotoThumbnail(record: rec, vm: vm)
                .frame(maxWidth: .infinity)
                .aspectRatio(3.0 / 4.0, contentMode: .fill)
                .clipShape(RoundedRectangle(cornerRadius: Corner.large, style: .continuous))
            Text(shortDate(rec.atMs))
                .font(.eggCaption)
                .foregroundStyle(palette.onSurface)
            Pill(text: label)
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Lightbox

private struct LightboxView: View {
    let record: PhotoRecord
    @ObservedObject var vm: PhotosViewModel
    let onClose: () -> Void

    @EnvironmentObject private var app: AppState
    @State private var image: UIImage?
    @State private var shareURL: URL?
    @State private var savedToast = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let image {
                ZoomableImage(image: image)
            } else {
                ProgressView().tint(.white)
            }

            VStack {
                HStack {
                    Button(action: onClose) {
                        Image(systemName: "xmark")
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(Spacing.m)
                    }
                    Spacer()
                    Text(longDate(record.atMs))
                        .font(.eggCallout)
                        .foregroundStyle(.white)
                        .padding(.trailing, Spacing.m)
                }

                Spacer()

                if savedToast {
                    Text("Enregistrée dans la pellicule")
                        .font(.eggCaption)
                        .foregroundStyle(.white)
                        .padding(.horizontal, Spacing.m)
                        .padding(.vertical, Spacing.s)
                        .background(.black.opacity(0.5), in: Capsule())
                        .padding(.bottom, Spacing.s)
                }

                HStack(spacing: Spacing.xl) {
                    if let shareURL {
                        ShareLink(item: shareURL) {
                            lightboxLabel("Partager", "square.and.arrow.up")
                        }
                    } else {
                        Button { Task { await prepareShare() } } label: {
                            lightboxLabel("Partager", "square.and.arrow.up")
                        }
                    }

                    Button { saveToCameraRoll() } label: {
                        lightboxLabel("Enregistrer", "square.and.arrow.down")
                    }

                    Button { delete() } label: {
                        lightboxLabel("Supprimer", "trash")
                    }
                }
                .padding(Spacing.xl)
            }
        }
        .task { await loadImage() }
        .onDisappear { cleanupShareTemp() }
    }

    private func lightboxLabel(_ text: String, _ symbol: String) -> some View {
        VStack(spacing: Spacing.xs) {
            Image(systemName: symbol)
                .font(.title3.weight(.semibold))
            Text(text).font(.eggCaption)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity)
    }

    private func loadImage() async {
        guard let session = app.session else { return }
        if let data = await vm.decrypt(record, session: session) {
            image = UIImage(data: data)
        }
    }

    /// Decrypt to a temporary plaintext file so ShareLink has something to
    /// hand off. Cleaned up on disappear via secureDelete.
    private func prepareShare() async {
        guard let session = app.session,
              let data = await vm.decrypt(record, session: session) else { return }
        let url = AppPaths.cacheDir
            .appendingPathComponent("photo-\(record.id)-\(Time.nowMs()).jpg")
        try? data.write(to: url, options: .completeFileProtection)
        shareURL = url
    }

    private func saveToCameraRoll() {
        guard let image else { return }
        UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
        withAnimation { savedToast = true }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            withAnimation { savedToast = false }
        }
    }

    private func delete() {
        guard let session = app.session else { return }
        Task {
            await vm.delete(record, session: session)
            onClose()
        }
    }

    private func cleanupShareTemp() {
        if let shareURL { AppPaths.secureDelete(shareURL) }
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
                        .font(.largeTitle)
                        .foregroundStyle(palette.onSurface.opacity(0.3))
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

private struct ZoomableImage: View {
    let image: UIImage
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1

    var body: some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .scaleEffect(scale)
            .gesture(
                MagnificationGesture()
                    .onChanged { value in scale = max(1, lastScale * value) }
                    .onEnded { _ in lastScale = scale }
            )
            .onTapGesture(count: 2) {
                withAnimation {
                    scale = scale > 1 ? 1 : 2.5
                    lastScale = scale
                }
            }
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
    let f = DateFormatter()
    f.locale = Locale(identifier: "fr_FR")
    f.dateFormat = "d MMM"
    return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
}

private func longDate(_ ms: Int64) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "fr_FR")
    f.dateFormat = "EEEE d MMMM yyyy"
    return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000)).capitalized
}

private func monthsBetween(_ fromMs: Int64, _ toMs: Int64) -> Int {
    let days = Int((toMs - fromMs) / 86_400_000)
    return max(0, days / 30)
}
