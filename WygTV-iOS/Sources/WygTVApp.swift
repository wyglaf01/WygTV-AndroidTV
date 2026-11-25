import SwiftUI
import AVKit
import Security

struct Account: Codable {
    var title = "My Playlist"
    var server = ""
    var username = ""
    var password = ""
    var key: String { server + "|" + username }
}

enum Vault {
    static let service = "com.wygtv.iphone.account"
    static func load() -> Account? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(Account.self, from: data)
    }
    static func save(_ account: Account) throws {
        let data = try JSONEncoder().encode(account)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        let update = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecItemNotFound {
            var add = query; add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw AppError.message("Could not save this playlist securely.") }
        } else if update != errSecSuccess { throw AppError.message("Could not update this playlist securely.") }
    }
}

enum AppError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}

enum LibraryKind: String, CaseIterable, Identifiable, Codable {
    case live, movie, series
    var id: String { rawValue }
    var title: String { switch self { case .live: return "Live TV"; case .movie: return "Movies"; case .series: return "Series" } }
    var symbol: String { switch self { case .live: return "tv"; case .movie: return "film"; case .series: return "rectangle.stack" } }
    var categoryAction: String { switch self { case .live: return "get_live_categories"; case .movie: return "get_vod_categories"; case .series: return "get_series_categories" } }
    var streamAction: String { switch self { case .live: return "get_live_streams"; case .movie: return "get_vod_streams"; case .series: return "get_series" } }
}

struct Media: Identifiable, Codable {
    let streamID: String
    let title: String
    let kind: LibraryKind
    let fileExtension: String
    var id: String { kind.rawValue + ":" + streamID }
}
struct Category: Identifiable { let id: String; let title: String }
struct Playback: Identifiable { let id = UUID(); let title: String; let url: URL }

struct XtreamAPI {
    let account: Account
    static func value(_ object: [String: Any], _ key: String) -> String {
        if let value = object[key] as? String { return value }
        if let value = object[key] as? NSNumber { return value.stringValue }
        return ""
    }
    func request(_ action: String? = nil, parameters: [String: String] = [:]) async throws -> Any {
        guard var url = URLComponents(string: account.server + "/player_api.php"),
              ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.host != nil else {
            throw AppError.message("Enter a valid HTTP or HTTPS provider address.")
        }
        var query = [URLQueryItem(name: "username", value: account.username), URLQueryItem(name: "password", value: account.password)]
        if let action { query.append(URLQueryItem(name: "action", value: action)) }
        query += parameters.map { URLQueryItem(name: $0.key, value: $0.value) }
        url.queryItems = query
        guard let endpoint = url.url else { throw AppError.message("Invalid provider address.") }
        var request = URLRequest(url: endpoint); request.timeoutInterval = 25
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw AppError.message("The provider returned an error. Check your address and account.")
        }
        do { return try JSONSerialization.jsonObject(with: data) }
        catch { throw AppError.message("The provider returned an unreadable response.") }
    }
    func authenticate() async throws {
        guard !account.username.isEmpty, !account.password.isEmpty else { throw AppError.message("Enter your username and password.") }
        let response = try await request() as? [String: Any]
        guard let user = response?["user_info"] as? [String: Any], Self.value(user, "auth") == "1",
              Self.value(user, "status").lowercased() == "active" else {
            throw AppError.message("Login rejected or account inactive. Your saved playlist was not changed.")
        }
    }
    func playback(_ media: Media) throws -> Playback {
        // Request HLS for Live TV: raw MPEG-TS URLs are not reliably supported by AVPlayer.
        let ext = media.kind == .live ? "m3u8" : media.fileExtension
        guard var url = URL(string: account.server) else { throw AppError.message("Invalid server address.") }
        for part in [media.kind.rawValue, account.username, account.password, media.streamID + "." + ext] {
            url.appendPathComponent(part)
        }
        return Playback(title: media.title, url: url)
    }
}

@MainActor final class AppModel: ObservableObject {
    @Published var account = Vault.load()
    @Published private(set) var favorites: [Media] = []
    init() { loadFavorites() }
    private var favoritesKey: String { "favorites." + (account?.key ?? "none").data(using: .utf8)!.base64EncodedString() }
    func loadFavorites() {
        favorites = (UserDefaults.standard.data(forKey: favoritesKey).flatMap { try? JSONDecoder().decode([Media].self, from: $0) }) ?? []
    }
    func connect(_ newAccount: Account) throws { try Vault.save(newAccount); account = newAccount; loadFavorites() }
    func toggle(_ media: Media) {
        if favorites.contains(where: { $0.id == media.id }) { favorites.removeAll { $0.id == media.id } }
        else { favorites.append(media) }
        if let data = try? JSONEncoder().encode(favorites) { UserDefaults.standard.set(data, forKey: favoritesKey) }
    }
}

@main struct WygTVApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene { WindowGroup { HomeScreen().environmentObject(model).preferredColorScheme(.dark).tint(.gold) } }
}
extension Color { static let gold = Color(red: 0.91, green: 0.77, blue: 0.48) }

struct HomeScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var showLogin = false
    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ZStack {
                    Color(red: 0.025, green: 0.05, blue: 0.11).ignoresSafeArea()
                    Image("Beowulf").resizable().scaledToFit().opacity(0.42).frame(maxWidth: .infinity, maxHeight: .infinity).ignoresSafeArea()
                    LinearGradient(colors: [.black.opacity(0.12), .black.opacity(0.75)], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
                    VStack(alignment: .leading, spacing: 20) {
                        Text("WygTV").font(.largeTitle.bold()).foregroundStyle(Color.gold)
                        Spacer(minLength: 12)
                        VStack(spacing: 12) {
                            Text("YOUR NEXT SAGA AWAITS").font(.caption).tracking(3).foregroundStyle(Color.gold)
                            Text("Choose your adventure").font(.title2.bold())
                            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: geometry.size.width > 650 ? 4 : 2), spacing: 14) {
                                ForEach(LibraryKind.allCases) { kind in
                                    NavigationLink { CatalogScreen(kind: kind) } label: { HomeTile(title: kind.title, symbol: kind.symbol) }
                                }
                                NavigationLink { FavoritesScreen() } label: { HomeTile(title: "Favorites", symbol: "star.fill") }
                            }
                        }
                        Spacer(minLength: 12)
                        HStack {
                            Button { showLogin = true } label: { Label("Manage Playlist", systemImage: "slider.horizontal.3").font(.subheadline.bold()).padding(12).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10)) }
                            Spacer()
                            Text(model.account?.title ?? "Connect a playlist").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }.padding(24)
                }
            }.toolbar(.hidden, for: .navigationBar)
        }.sheet(isPresented: $showLogin) { LoginScreen() }
    }
}
struct HomeTile: View {
    let title: String; let symbol: String
    var body: some View {
        VStack(spacing: 12) { Image(systemName: symbol).font(.title); Text(title).font(.headline) }
            .foregroundStyle(Color.gold).frame(maxWidth: .infinity).frame(height: 108)
            .background(Color(red: 0.05, green: 0.09, blue: 0.16).opacity(0.9), in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.gold.opacity(0.6)))
    }
}

struct LoginScreen: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var account = Account()
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section("Xtream playlist") {
                    TextField("Playlist name", text: $account.title)
                    TextField("Server URL", text: $account.server).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Username", text: $account.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Password", text: $account.password)
                }.disabled(busy)
                if let error { Text(error).foregroundStyle(.red) }
                Button {
                    busy = true; error = nil
                    Task {
                        defer { busy = false }
                        do {
                            var candidate = account
                            candidate.server = candidate.server.trimmingCharacters(in: .whitespacesAndNewlines)
                            while candidate.server.hasSuffix("/") { candidate.server.removeLast() }
                            try await XtreamAPI(account: candidate).authenticate()
                            try model.connect(candidate); dismiss()
                        } catch { self.error = error.localizedDescription }
                    }
                } label: { if busy { ProgressView() } else { Text("Save & Connect") } }.disabled(busy)
            }.navigationTitle("Manage Playlist").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() }.disabled(busy) } }
        }.onAppear { account = model.account ?? Account() }.interactiveDismissDisabled(busy)
    }
}

struct CatalogScreen: View {
    @EnvironmentObject var model: AppModel
    let kind: LibraryKind
    @State private var categories: [Category] = []
    @State private var selected = ""
    @State private var items: [Media] = []
    @State private var search = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        Group {
            if model.account == nil { Text("Connect a playlist using Manage Playlist on the home screen.").padding() }
            else {
                List {
                    Picker("Category", selection: $selected) { Text("All categories").tag(""); ForEach(categories) { Text($0.title).tag($0.id) } }
                    if busy { ProgressView("Loading…") }
                    if let error { Text(error).foregroundStyle(.red); Button("Retry") { Task { await load() } } }
                    if !busy && error == nil && items.isEmpty { Text("No content in this category.").foregroundStyle(.secondary) }
                    ForEach(items.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) }) { item in
                        NavigationLink { MediaScreen(media: item) } label: { Label(item.title, systemImage: kind.symbol) }
                    }
                }.searchable(text: $search)
            }
        }.navigationTitle(kind.title)
        .task(id: selected) { await load() }
    }
    @MainActor func load() async {
        guard let account = model.account else { return }
        busy = true; error = nil; items = []
        defer { busy = false }
        do {
            let api = XtreamAPI(account: account)
            if categories.isEmpty {
                let response = try await api.request(kind.categoryAction) as? [[String: Any]] ?? []
                try Task.checkCancellation()
                categories = response.map { Category(id: XtreamAPI.value($0, "category_id"), title: XtreamAPI.value($0, "category_name")) }
            }
            let response = try await api.request(kind.streamAction, parameters: selected.isEmpty ? [:] : ["category_id": selected]) as? [[String: Any]] ?? []
            try Task.checkCancellation()
            items = response.compactMap { object in
                let id = XtreamAPI.value(object, kind == .series ? "series_id" : "stream_id")
                guard !id.isEmpty else { return nil }
                let ext = XtreamAPI.value(object, "container_extension")
                return Media(streamID: id, title: XtreamAPI.value(object, "name"), kind: kind, fileExtension: ext.isEmpty ? "mp4" : ext)
            }
        } catch is CancellationError {} catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
}

struct FavoritesScreen: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        List {
            if model.favorites.isEmpty { Text("Use the star on a title to add a favorite.").foregroundStyle(.secondary) }
            ForEach(model.favorites) { item in NavigationLink { MediaScreen(media: item) } label: { Label(item.title, systemImage: item.kind.symbol) } }
                .onDelete { offsets in for index in offsets.sorted(by: >) { model.toggle(model.favorites[index]) } }
        }.navigationTitle("Favorites")
    }
}

struct MediaScreen: View {
    @EnvironmentObject var model: AppModel
    let media: Media
    @State private var playback: Playback?
    @State private var episodes: [(season: String, items: [Media])] = []
    @State private var guide = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        List {
            Section {
                if media.kind != .series { Button { play(media) } label: { Label("Play", systemImage: "play.fill") } }
                Button { model.toggle(media) } label: { Label(model.favorites.contains(where: { $0.id == media.id }) ? "Remove Favorite" : "Add Favorite", systemImage: "star") }
            }
            if busy { ProgressView("Loading…") }
            if let error { Text(error).foregroundStyle(.red); Button("Retry") { Task { await loadDetails() } } }
            if media.kind == .live { Section("Program guide") { Text(guide.isEmpty ? "No guide available." : guide) } }
            if media.kind == .series {
                ForEach(episodes, id: \.season) { season in Section("Season \(season.season)") { ForEach(season.items) { episode in Button(episode.title) { play(episode) } } } }
                if !busy && episodes.isEmpty && error == nil { Text("No episodes available.") }
            }
        }.navigationTitle(media.title).task { await loadDetails() }
        .fullScreenCover(item: $playback) { PlayerScreen(playback: $0) }
    }
    func play(_ item: Media) {
        guard let account = model.account else { return }
        do { playback = try XtreamAPI(account: account).playback(item) } catch { self.error = error.localizedDescription }
    }
    @MainActor func loadDetails() async {
        guard let account = model.account, media.kind != .movie else { return }
        busy = true; error = nil
        defer { busy = false }
        do {
            let api = XtreamAPI(account: account)
            if media.kind == .series {
                let response = try await api.request("get_series_info", parameters: ["series_id": media.streamID]) as? [String: Any] ?? [:]
                try Task.checkCancellation()
                let seasons = response["episodes"] as? [String: [[String: Any]]] ?? [:]
                episodes = seasons.keys.sorted { $0.localizedStandardCompare($1) == .orderedAscending }.map { season in
                    (season, (seasons[season] ?? []).compactMap { object in
                        let id = XtreamAPI.value(object, "id"); guard !id.isEmpty else { return nil }
                        let ext = XtreamAPI.value(object, "container_extension")
                        return Media(streamID: id, title: XtreamAPI.value(object, "title"), kind: .series, fileExtension: ext.isEmpty ? "mp4" : ext)
                    })
                }
            } else {
                let response = try await api.request("get_short_epg", parameters: ["stream_id": media.streamID, "limit": "15"]) as? [String: Any] ?? [:]
                try Task.checkCancellation()
                func decoded(_ text: String) -> String { Data(base64Encoded: text).flatMap { String(data: $0, encoding: .utf8) } ?? text }
                guide = (response["epg_listings"] as? [[String: Any]] ?? []).map {
                    XtreamAPI.value($0, "start") + "\n" + decoded(XtreamAPI.value($0, "title")) + "\n" + decoded(XtreamAPI.value($0, "description"))
                }.joined(separator: "\n\n")
            }
        } catch is CancellationError {} catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
}

struct PlayerScreen: View {
    let playback: Playback
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var player: AVPlayer
    @State private var failure: String?
    init(playback: Playback) { self.playback = playback; _player = State(initialValue: AVPlayer(url: playback.url)) }
    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            VideoPlayer(player: player).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 12) {
                Button { dismiss() } label: { Label("Close", systemImage: "xmark.circle.fill").padding(12).background(.ultraThinMaterial, in: Capsule()) }
                if let failure { Text(failure).padding().background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12)) }
            }.padding()
        }.onAppear { player.play() }.onDisappear { player.pause(); player.replaceCurrentItem(with: nil) }
        .onChange(of: scenePhase) { phase in if phase != .active { player.pause() } }
        .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime)) { notice in
            guard let item = notice.object as? AVPlayerItem, item === player.currentItem else { return }
            failure = "Playback failed. This provider must supply an iPhone-compatible HLS or MP4 stream."
        }
        .task {
            while !Task.isCancelled {
                if player.currentItem?.status == .failed { failure = "This stream could not be opened. Check the provider and try an HLS or MP4 stream."; return }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }
}
