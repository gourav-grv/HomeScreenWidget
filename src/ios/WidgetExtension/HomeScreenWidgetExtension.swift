import WidgetKit
import SwiftUI
import AppIntents

// MARK: - Shared storage

enum HSWShared {
    static var group: String { Bundle.main.object(forInfoDictionaryKey: "HSWAppGroup") as? String ?? "" }
    static var scheme: String { Bundle.main.object(forInfoDictionaryKey: "HSWScheme") as? String ?? "" }
    static var defaults: UserDefaults? { group.isEmpty ? nil : UserDefaults(suiteName: group) }

    static func object(_ raw: String?) -> [String: Any]? {
        guard let raw = raw, let data = raw.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    /// Runs a request with the stored headers; stores the response when it is widget data.
    static func request(key: String, url: String, method: String?, body: String?) async {
        guard let u = URL(string: url) else { return }
        var req = URLRequest(url: u, timeoutInterval: 10)
        let hasBody = !(body ?? "").isEmpty
        req.httpMethod = (method?.isEmpty == false) ? method!.uppercased() : (hasBody ? "POST" : "GET")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if hasBody, req.httpMethod != "GET" {
            req.httpBody = body!.data(using: .utf8)
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        if let headers = object(defaults?.string(forKey: "hsw_headers")) {
            for (k, v) in headers { req.setValue("\(v)", forHTTPHeaderField: k) }
        }
        guard let (data, response) = try? await URLSession.shared.data(for: req),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              let text = String(data: data, encoding: .utf8),
              let obj = object(text), obj["Rows"] != nil || obj["Title"] != nil else { return }
        defaults?.set(text, forKey: "hsw_data_" + key)
    }
}

// MARK: - Model

struct HSWButton: Identifiable {
    let id: String
    let label: String
    let actionType: String
    let raw: [String: Any]
}

struct HSWRow: Identifiable {
    let id: String
    let type: String
    let text: String
    let subtext: String
    let done: Bool
    let progress: Int
    let imageUrl: String
    let deepLink: String
    let buttons: [HSWButton]
}

struct HSWData {
    var title = ""
    var subtitle = ""
    var count = 0
    var countLabel = ""
    var footer = ""
    var emptyText = "Nothing to show. Open the app to sync."
    var deepLink = ""
    var refreshUrl = ""
    var accent = Color(red: 0x10 / 255, green: 0x68 / 255, blue: 0xEB / 255)
    var rows: [HSWRow] = []

    var key = "main"

    static func load(_ key: String) -> HSWData {
        var d = HSWData()
        d.key = key
        guard let obj = HSWShared.object(HSWShared.defaults?.string(forKey: "hsw_data_" + key)) else { return d }
        d.title = obj["Title"] as? String ?? ""
        d.subtitle = obj["Subtitle"] as? String ?? ""
        d.count = (obj["Count"] as? NSNumber)?.intValue ?? 0
        d.countLabel = obj["CountLabel"] as? String ?? ""
        d.footer = obj["Footer"] as? String ?? ""
        if let e = obj["EmptyText"] as? String, !e.isEmpty { d.emptyText = e }
        d.deepLink = obj["DeepLink"] as? String ?? ""
        d.refreshUrl = obj["RefreshUrl"] as? String ?? ""
        if let hex = obj["AccentColor"] as? String, let c = Color(hex: hex) { d.accent = c }
        let rows = obj["Rows"] as? [[String: Any]] ?? []
        d.rows = rows.prefix(25).enumerated().map { idx, r in
            let buttons = (r["Buttons"] as? [[String: Any]] ?? []).prefix(2).enumerated().map { bi, b in
                HSWButton(id: (b["Id"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "b\(bi)",
                          label: b["Label"] as? String ?? "→",
                          actionType: b["ActionType"] as? String ?? "openApp",
                          raw: b)
            }
            return HSWRow(id: (r["Id"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "r\(idx)",
                          type: (r["Type"] as? String ?? "item").lowercased(),
                          text: r["Text"] as? String ?? "",
                          subtext: r["Subtext"] as? String ?? "",
                          done: r["IsDone"] as? Bool ?? false,
                          progress: max(0, min(100, (r["Progress"] as? NSNumber)?.intValue ?? 0)),
                          imageUrl: r["ImageUrl"] as? String ?? "",
                          deepLink: r["DeepLink"] as? String ?? "",
                          buttons: Array(buttons))
        }
        return d
    }

    /// URL that opens the app and hands the action to the plugin (hsw-<bundle id>://action?a=json).
    func actionURL(_ action: [String: Any]) -> URL? {
        guard !HSWShared.scheme.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: action),
              let json = String(data: data, encoding: .utf8) else { return nil }
        var c = URLComponents()
        c.scheme = HSWShared.scheme
        c.host = "action"
        c.queryItems = [URLQueryItem(name: "a", value: json)]
        return c.url
    }

    func rowAction(_ row: HSWRow) -> [String: Any] {
        ["ActionType": "openApp", "ActionName": "RowTap", "RowId": row.id, "WidgetKey": key,
         "DeepLink": row.deepLink.isEmpty ? deepLink : row.deepLink]
    }

    func buttonAction(_ row: HSWRow, _ b: HSWButton) -> [String: Any] {
        ["ActionType": b.actionType,
         "ActionName": b.raw["ActionName"] as? String ?? "",
         "RowId": row.id, "ButtonId": b.id, "WidgetKey": key,
         "Payload": b.raw["Payload"] as? String ?? "",
         "Url": b.raw["Url"] as? String ?? "",
         "Method": b.raw["Method"] as? String ?? "",
         "Body": b.raw["Body"] as? String ?? ""]
    }
}

extension Color {
    init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

// MARK: - Interactive button (iOS 17+): runs an http action without opening the app

@available(iOS 17.0, *)
struct HSWHttpIntent: AppIntent {
    static var title: LocalizedStringResource = "Widget action"
    static var isDiscoverable: Bool = false

    @Parameter(title: "Key") var key: String
    @Parameter(title: "Url") var url: String
    @Parameter(title: "Method") var method: String
    @Parameter(title: "Body") var body: String

    init() {}

    init(key: String, url: String, method: String, body: String) {
        self.key = key
        self.url = url
        self.method = method
        self.body = body
    }

    func perform() async throws -> some IntentResult {
        await HSWShared.request(key: key, url: url, method: method, body: body)
        let data = HSWData.load(key)
        if !data.refreshUrl.isEmpty, data.refreshUrl != url {
            await HSWShared.request(key: key, url: data.refreshUrl, method: "GET", body: nil)
        }
        return .result()
    }
}

// MARK: - Timeline

struct HSWEntry: TimelineEntry {
    let date: Date
    let data: HSWData
    let images: [String: UIImage]
}

struct HSWProvider: TimelineProvider {
    let key: String

    func placeholder(in context: Context) -> HSWEntry {
        var d = HSWData()
        d.title = "Tasks"
        d.count = 2
        d.countLabel = "pending"
        d.rows = [HSWRow(id: "1", type: "item", text: "Sample item", subtext: "", done: false, progress: 0,
                         imageUrl: "", deepLink: "", buttons: [])]
        return HSWEntry(date: Date(), data: d, images: [:])
    }

    func getSnapshot(in context: Context, completion: @escaping (HSWEntry) -> Void) {
        if context.isPreview { return completion(placeholder(in: context)) }
        completion(HSWEntry(date: Date(), data: HSWData.load(key), images: [:]))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<HSWEntry>) -> Void) {
        Task {
            var data = HSWData.load(key)
            if !data.refreshUrl.isEmpty {
                await HSWShared.request(key: key, url: data.refreshUrl, method: "GET", body: nil)
                data = HSWData.load(key)
            }
            var images: [String: UIImage] = [:]
            for row in data.rows where row.type == "image" && !row.imageUrl.isEmpty {
                if let u = URL(string: row.imageUrl),
                   let (bytes, _) = try? await URLSession.shared.data(from: u),
                   let img = UIImage(data: bytes) {
                    images[row.id] = img.preparingThumbnail(of: CGSize(width: 600, height: 600 * img.size.height / max(img.size.width, 1))) ?? img
                }
            }
            let next = Calendar.current.date(byAdding: .minute, value: 30, to: Date()) ?? Date()
            completion(Timeline(entries: [HSWEntry(date: Date(), data: data, images: images)], policy: .after(next)))
        }
    }
}

// MARK: - Views

struct HSWView: View {
    @Environment(\.widgetFamily) var family
    let entry: HSWEntry

    private var maxRows: Int {
        switch family {
        case .systemSmall: return 2
        case .systemMedium: return 3
        default: return 8
        }
    }

    var body: some View {
        let d = entry.data
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 8) {
                RoundedRectangle(cornerRadius: 2).fill(d.accent).frame(width: 4, height: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text(d.title.isEmpty ? "Widget" : d.title).font(.system(size: 15, weight: .bold)).lineLimit(1)
                    if !d.subtitle.isEmpty {
                        Text(d.subtitle).font(.system(size: 12)).foregroundColor(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                if !d.countLabel.isEmpty || d.count != 0 {
                    VStack(alignment: .trailing, spacing: 0) {
                        Text("\(d.count)").font(.system(size: 22, weight: .bold)).foregroundColor(d.accent)
                        if !d.countLabel.isEmpty {
                            Text(d.countLabel).font(.system(size: 10)).foregroundColor(.secondary)
                        }
                    }
                }
            }

            if d.rows.isEmpty {
                Spacer()
                Text(d.emptyText).font(.system(size: 13)).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                Spacer()
            } else {
                ForEach(d.rows.prefix(maxRows)) { row in
                    rowView(row, d)
                }
                Spacer(minLength: 0)
            }

            if !d.footer.isEmpty && family != .systemSmall {
                Text(d.footer).font(.system(size: 11)).foregroundColor(.secondary).lineLimit(1)
            }
        }
        .padding(family == .systemSmall ? 2 : 4)
        .widgetURL(d.actionURL(["ActionType": "openApp", "ActionName": "Open", "WidgetKey": d.key, "DeepLink": d.deepLink]))
    }

    @ViewBuilder
    private func rowView(_ row: HSWRow, _ d: HSWData) -> some View {
        let content = rowContent(row, d)
        if family != .systemSmall, let url = d.actionURL(d.rowAction(row)) {
            Link(destination: url) { content }
        } else {
            content
        }
    }

    @ViewBuilder
    private func rowContent(_ row: HSWRow, _ d: HSWData) -> some View {
        switch row.type {
        case "text":
            VStack(alignment: .leading, spacing: 1) {
                Text(row.text).font(.system(size: 13)).lineLimit(2)
                if !row.subtext.isEmpty { Text(row.subtext).font(.system(size: 11)).foregroundColor(.secondary).lineLimit(1) }
            }
        case "progress":
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(row.text).font(.system(size: 13)).lineLimit(1)
                    Spacer()
                    Text("\(row.progress)%").font(.system(size: 12, weight: .bold)).foregroundColor(d.accent)
                }
                ProgressView(value: Double(row.progress), total: 100).tint(d.accent)
            }
        case "image":
            VStack(alignment: .leading, spacing: 2) {
                if let img = entry.images[row.id] {
                    Image(uiImage: img).resizable().scaledToFill().frame(height: 80).clipped().cornerRadius(6)
                }
                if !row.text.isEmpty { Text(row.text).font(.system(size: 11)).foregroundColor(.secondary).lineLimit(1) }
            }
        default:
            HStack(spacing: 6) {
                Image(systemName: row.done ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 13))
                    .foregroundColor(row.done ? .secondary : d.accent)
                VStack(alignment: .leading, spacing: 0) {
                    Text(row.text).font(.system(size: 13)).lineLimit(1)
                        .foregroundColor(row.done ? .secondary : .primary)
                    if !row.subtext.isEmpty {
                        Text(row.subtext).font(.system(size: 11)).foregroundColor(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 2)
                if family != .systemSmall {
                    ForEach(row.buttons) { b in buttonView(row, b, d) }
                }
            }
        }
    }

    @ViewBuilder
    private func buttonView(_ row: HSWRow, _ b: HSWButton, _ d: HSWData) -> some View {
        let label = Text(b.label).font(.system(size: 12, weight: .bold)).foregroundColor(d.accent)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(RoundedRectangle(cornerRadius: 10).fill(d.accent.opacity(0.12)))
        let isHttp = b.actionType.caseInsensitiveCompare("http") == .orderedSame
        if isHttp {
            if #available(iOSApplicationExtension 17.0, *) {
                Button(intent: HSWHttpIntent(key: d.key, url: b.raw["Url"] as? String ?? "",
                                             method: b.raw["Method"] as? String ?? "",
                                             body: b.raw["Body"] as? String ?? "")) { label }
                    .buttonStyle(.plain)
            } else {
                linkOrLabel(label, d.actionURL(d.buttonAction(row, b)))
            }
        } else {
            linkOrLabel(label, d.actionURL(d.buttonAction(row, b)))
        }
    }

    @ViewBuilder
    private func linkOrLabel<V: View>(_ label: V, _ url: URL?) -> some View {
        if let url = url {
            Link(destination: url) { label }
        } else {
            label
        }
    }
}

struct HSWBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            content.containerBackground(for: .widget) { Color(UIColor.systemBackground) }
        } else {
            content.padding().background(Color(UIColor.systemBackground))
        }
    }
}

/// One widget type. The hook generates HSWWidgetTypes.swift: a Widget struct per
/// HSWWidgetTypes entry plus the @main WidgetBundle, each calling this.
func hswConfiguration(key: String, name: String) -> some WidgetConfiguration {
    StaticConfiguration(kind: "HSW_" + key, provider: HSWProvider(key: key)) { entry in
        HSWView(entry: entry).modifier(HSWBackground())
    }
    .configurationDisplayName(name)
    .description("Quick view of your app on the home screen")
    .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
}
