import SwiftUI
import TransitionCore
import PhotosUI

@MainActor
final class PhotosViewModel: ObservableObject {
    @Published var loading = true
    @Published var records: [PhotoRecord] = []
    @Published var images: [Int64: UIImage] = [:]
    @Published var error: String?

    func load(_ session: VaultService) async {
        loading = true
        do {
            let recs = try await session.listPhotoRecords()
            var map: [Int64: UIImage] = [:]
            for rec in recs {
                let data = try await session.decryptBlobFile(URL(fileURLWithPath: rec.filePath))
                if let img = UIImage(data: data) {
                    map[rec.id] = img
                }
            }
            records = recs
            images = map
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    func importPhoto(_ data: Data, session: VaultService) async {
        do {
            let url = try await session.encryptBlobToFile(data, in: AppPaths.photosDir)
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
            try? FileManager.default.removeItem(at: URL(fileURLWithPath: rec.filePath))
            await load(session)
        } catch {
            self.error = describe(error)
        }
    }
}

struct PhotosView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.palette) private var palette
    @StateObject private var vm = PhotosViewModel()
    @State private var pickerItem: PhotosPickerItem?
    @State private var selectedId: Int64?

    private let columns = [GridItem(.flexible(), spacing: Spacing.m),
                           GridItem(.flexible(), spacing: Spacing.m)]

    var body: some View {
        TabScaffold(title: "Photos") {
            if vm.loading {
                ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
            } else if vm.records.isEmpty {
                EmptyStateCard(text: "Aucune photo", systemImage: "photo")
            } else {
                LazyVGrid(columns: columns, spacing: Spacing.m) {
                    ForEach(vm.records, id: \.id) { rec in
                        thumbnail(rec)
                    }
                }
            }
            if let e = vm.error { ErrorBanner(message: e) }
        }
        .overlay(alignment: .bottomTrailing) {
            PhotosPicker(selection: $pickerItem, matching: .images) {
                Image(systemName: "camera.fill")
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
                lightbox(rec)
            }
        }
        .onChange(of: pickerItem) { _, newItem in
            guard let newItem else { return }
            Task {
                if let data = try? await newItem.loadTransferable(type: Data.self),
                   let session = app.session {
                    await vm.importPhoto(data, session: session)
                }
                pickerItem = nil
            }
        }
        .task { if let s = app.session { await vm.load(s) } }
    }

    private func thumbnail(_ rec: PhotoRecord) -> some View {
        Button { selectedId = rec.id } label: {
            Group {
                if let img = vm.images[rec.id] {
                    Image(uiImage: img)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: "photo")
                        .font(.largeTitle)
                        .foregroundStyle(palette.onSurface.opacity(0.4))
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 160)
            .clipped()
            .background(palette.surfaceContainer)
            .clipShape(RoundedRectangle(cornerRadius: Corner.large, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private func lightbox(_ rec: PhotoRecord) -> some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let img = vm.images[rec.id] {
                ZoomableImage(image: img)
            }
            VStack {
                HStack {
                    Button {
                        selectedId = nil
                    } label: {
                        Image(systemName: "xmark")
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(Spacing.m)
                    }
                    Spacer()
                }
                Spacer()
                Button {
                    if let session = app.session {
                        Task {
                            await vm.delete(rec, session: session)
                            selectedId = nil
                        }
                    }
                } label: {
                    Label("Supprimer", systemImage: "trash")
                        .font(.eggCallout)
                        .frame(maxWidth: .infinity)
                }
                .glassButton().tint(palette.error)
                .padding(Spacing.xl)
            }
        }
    }
}

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
                    .onChanged { value in
                        scale = max(1, lastScale * value)
                    }
                    .onEnded { _ in
                        lastScale = scale
                    }
            )
            .onTapGesture(count: 2) {
                withAnimation {
                    scale = scale > 1 ? 1 : 2.5
                    lastScale = scale
                }
            }
    }
}
