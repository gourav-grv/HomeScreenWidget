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
    static func request(url: String, method: String?, body: String?) async {
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
        defaults?.set(text, forKey: "hsw_data")
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
    var nodes: [HSWNode] = []

    static func load() -> HSWData {
        var d = HSWData()
        guard let obj = HSWShared.object(HSWShared.defaults?.string(forKey: "hsw_data")) else { return d }
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
        d.nodes = (obj["Nodes"] as? [[String: Any]] ?? []).prefix(80).enumerated().map { HSWNode(raw: $1, index: $0) }
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
        ["ActionType": "openApp", "ActionName": "RowTap", "RowId": row.id,
         "DeepLink": row.deepLink.isEmpty ? deepLink : row.deepLink]
    }

    func buttonAction(_ row: HSWRow, _ b: HSWButton) -> [String: Any] {
        ["ActionType": b.actionType,
         "ActionName": b.raw["ActionName"] as? String ?? "",
         "RowId": row.id, "ButtonId": b.id,
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

    @Parameter(title: "Url") var url: String
    @Parameter(title: "Method") var method: String
    @Parameter(title: "Body") var body: String

    init() {}

    init(url: String, method: String, body: String) {
        self.url = url
        self.method = method
        self.body = body
    }

    func perform() async throws -> some IntentResult {
        await HSWShared.request(url: url, method: method, body: body)
        let data = HSWData.load()
        if !data.refreshUrl.isEmpty, data.refreshUrl != url {
            await HSWShared.request(url: data.refreshUrl, method: "GET", body: nil)
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
        completion(HSWEntry(date: Date(), data: HSWData.load(), images: [:]))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<HSWEntry>) -> Void) {
        Task {
            var data = HSWData.load()
            if !data.refreshUrl.isEmpty {
                await HSWShared.request(url: data.refreshUrl, method: "GET", body: nil)
                data = HSWData.load()
            }
            var images: [String: UIImage] = [:]
            let imageItems = data.rows.filter { $0.type == "image" }.map { ($0.id, $0.imageUrl) }
                + data.nodes.filter { $0.type == "image" }.map { ($0.id, $0.imageUrl) }
            for (imageId, imageUrl) in imageItems where !imageUrl.isEmpty {
                if let u = URL(string: imageUrl),
                   let (bytes, _) = try? await URLSession.shared.data(from: u),
                   let img = UIImage(data: bytes) {
                    images[imageId] = img.preparingThumbnail(of: CGSize(width: 600, height: 600 * img.size.height / max(img.size.width, 1))) ?? img
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
        if entry.data.nodes.isEmpty {
            classicBody
        } else {
            HSWTreeView(data: entry.data, images: entry.images)
        }
    }

    private var classicBody: some View {
        let d = entry.data
        return VStack(alignment: .leading, spacing: 6) {
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
        .widgetURL(d.actionURL(["ActionType": "openApp", "ActionName": "Open", "DeepLink": d.deepLink]))
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
                Button(intent: HSWHttpIntent(url: b.raw["Url"] as? String ?? "",
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
    var data: HSWData? = nil

    private var fill: AnyView {
        if let d = data, let root = HSWTree(nodes: d.nodes).root {
            return HSWStyle.background(root.background.isEmpty ? "#FFFFFF" : root.background, radius: 0)
        }
        return AnyView(Color(UIColor.systemBackground))
    }

    func body(content: Content) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            content.containerBackground(for: .widget) { fill }
        } else {
            content.padding().background(fill)
        }
    }
}

@main
struct HomeScreenWidget: Widget {
    let kind = "HomeScreenWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: HSWProvider()) { entry in
            HSWView(entry: entry).modifier(HSWBackground(data: entry.data))
        }
        .configurationDisplayName(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "Widget")
        .description("Quick view of your app on the home screen")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}


// MARK: - Custom UI (WidgetData.Nodes)

struct HSWNode: Identifiable {
    let id: String
    let parentId: String
    let type: String
    let text: String
    let value: Double
    let max: Double
    let imageUrl: String
    let color: String
    let background: String
    let fontSize: Double
    let bold: Bool
    let align: String
    let weight: Double
    let padding: Double
    let gap: Double
    let width: Double
    let height: Double
    let checked: Bool
    let raw: [String: Any]

    init(raw r: [String: Any], index: Int) {
        func s(_ k: String) -> String { r[k] as? String ?? "" }
        func n(_ k: String) -> Double { (r[k] as? NSNumber)?.doubleValue ?? 0 }
        let rawId = s("Id")
        id = rawId.isEmpty ? "n\(index)" : rawId
        parentId = s("ParentId")
        type = s("Type").lowercased()
        text = s("Text")
        value = n("Value")
        max = n("Max")
        imageUrl = s("ImageUrl")
        color = s("Color")
        background = s("Background")
        fontSize = n("FontSize")
        bold = r["Bold"] as? Bool ?? false
        align = s("Align").lowercased()
        weight = n("Weight")
        padding = n("Padding")
        gap = n("Gap")
        width = n("Width")
        height = n("Height")
        checked = r["Checked"] as? Bool ?? false
        raw = r
    }

    var hasAction: Bool { !((raw["ActionType"] as? String) ?? "").isEmpty }

    var fraction: Double {
        let m = max > 0 ? max : 100
        return Swift.max(0, Swift.min(1, value / m))
    }
}

struct HSWTree {
    let byParent: [String: [HSWNode]]
    let root: HSWNode?

    init(nodes: [HSWNode]) {
        let ids = Set(nodes.map { $0.id })
        var map: [String: [HSWNode]] = [:]
        var roots: [HSWNode] = []
        for n in nodes {
            if n.parentId.isEmpty || !ids.contains(n.parentId) || n.parentId == n.id {
                roots.append(n)
            } else {
                map[n.parentId, default: []].append(n)
            }
        }
        if roots.count == 1 {
            root = roots[0]
        } else if roots.isEmpty {
            root = nil
        } else {
            let wrapper = HSWNode(raw: ["Id": "__root", "Type": "column"], index: -1)
            map["__root"] = roots
            root = wrapper
        }
        byParent = map
    }

    func children(_ n: HSWNode) -> [HSWNode] { byParent[n.id] ?? [] }
}

enum HSWStyle {
    static func color(_ s: String, _ fallback: Color) -> Color {
        let parts = s.split(separator: ",").map { String($0) }
        guard let first = parts.first, let c = Color(hex: first) else { return fallback }
        return c
    }

    static func background(_ spec: String, radius: CGFloat) -> AnyView {
        let s = spec.trimmingCharacters(in: .whitespaces)
        if s.isEmpty { return AnyView(Color.clear) }
        if s.hasPrefix("#") {
            let colors = s.split(separator: ",").compactMap { Color(hex: String($0)) }
            if colors.count > 1 {
                return AnyView(RoundedRectangle(cornerRadius: radius)
                    .fill(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)))
            }
            return AnyView(RoundedRectangle(cornerRadius: radius).fill(colors.first ?? .clear))
        }
        switch s.lowercased() {
        case "card": return AnyView(RoundedRectangle(cornerRadius: radius).fill(Color.white))
        case "light": return AnyView(RoundedRectangle(cornerRadius: radius).fill(Color(white: 0.95)))
        case "dark": return AnyView(RoundedRectangle(cornerRadius: radius).fill(Color(red: 0.12, green: 0.16, blue: 0.22)))
        case "glass", "chip": return AnyView(RoundedRectangle(cornerRadius: radius).fill(Color.white.opacity(0.18)))
        case "accent": return AnyView(RoundedRectangle(cornerRadius: radius).fill(Color(red: 0.39, green: 0.40, blue: 0.95)))
        case "circle": return AnyView(Circle().fill(Color.white))
        case "circleaccent": return AnyView(Circle().fill(Color(red: 0.39, green: 0.40, blue: 0.95)))
        default: return AnyView(Color.clear)
        }
    }

    static func hAlign(_ a: String) -> HorizontalAlignment {
        switch a { case "center": return .center; case "end", "right": return .trailing; default: return .leading }
    }

    static func vAlign(_ a: String) -> VerticalAlignment {
        switch a { case "top": return .top; case "bottom": return .bottom; default: return .center }
    }

    static func frameAlign(_ a: String) -> Alignment {
        switch a {
        case "center": return .center
        case "end", "right": return .trailing
        case "top": return .top
        case "bottom": return .bottom
        case "start", "left": return .leading
        default: return .center
        }
    }
}

struct HSWTreeView: View {
    let data: HSWData
    let images: [String: UIImage]

    var body: some View {
        let tree = HSWTree(nodes: data.nodes)
        if let root = tree.root {
            render(root, tree, inherited: Color(hex: "#111827") ?? .primary, isTop: true)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        } else {
            Color.clear
        }
    }

    private func render(_ n: HSWNode, _ tree: HSWTree, inherited: Color, isTop: Bool = false) -> AnyView {
        let color = HSWStyle.color(n.color, inherited)
        var view: AnyView
        switch n.type {
        case "row":
            view = AnyView(HStack(alignment: HSWStyle.vAlign(n.align), spacing: CGFloat(n.gap)) {
                ForEach(tree.children(n)) { c in weighted(render(c, tree, inherited: color), c, horizontal: true) }
            })
        case "column":
            view = AnyView(VStack(alignment: HSWStyle.hAlign(n.align), spacing: CGFloat(n.gap)) {
                ForEach(tree.children(n)) { c in weighted(render(c, tree, inherited: color), c, horizontal: false) }
            })
        case "stack":
            view = AnyView(ZStack {
                ForEach(tree.children(n)) { c in
                    render(c, tree, inherited: color)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: HSWStyle.frameAlign(c.align))
                }
            })
        case "image":
            if let img = images[n.id] {
                view = AnyView(Image(uiImage: img).resizable().scaledToFill()
                    .frame(width: n.width > 0 ? CGFloat(n.width) : nil, height: n.height > 0 ? CGFloat(n.height) : 80)
                    .clipped().cornerRadius(8))
            } else {
                view = AnyView(Color.clear.frame(width: n.width > 0 ? CGFloat(n.width) : 1, height: n.height > 0 ? CGFloat(n.height) : 1))
            }
        case "ring":
            let size = CGFloat(n.width > 0 ? n.width : (n.height > 0 ? n.height : 64))
            let ringColor = HSWStyle.color(n.color, Color(red: 0.39, green: 0.40, blue: 0.95))
            view = AnyView(ZStack {
                Circle().stroke(ringColor.opacity(0.2), lineWidth: max(4, size / 10))
                Circle().trim(from: 0, to: CGFloat(n.fraction))
                    .stroke(ringColor, style: StrokeStyle(lineWidth: max(4, size / 10), lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text(n.text).font(.system(size: CGFloat(n.fontSize > 0 ? n.fontSize : Double(max(10, size / 5))), weight: .bold))
                    .foregroundColor(inherited).multilineTextAlignment(.center)
            }.frame(width: size, height: size).padding(max(2, size / 20)))
        case "bar", "divider":
            let isDivider = n.type == "divider"
            let h = CGFloat(n.height > 0 ? n.height : (isDivider ? 1 : 6))
            let barColor = HSWStyle.color(n.color, isDivider ? Color(white: 0.9) : Color(red: 0.39, green: 0.40, blue: 0.95))
            let track = isDivider ? barColor : HSWStyle.color(n.background, barColor.opacity(0.2))
            let frac = isDivider ? 1.0 : n.fraction
            view = AnyView(GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(track)
                    Capsule().fill(barColor).frame(width: g.size.width * CGFloat(frac))
                }
            }.frame(height: h))
        case "checkbox", "toggle":
            let accent = HSWStyle.color(n.background, Color(red: 0.39, green: 0.40, blue: 0.95))
            let symbol: String = n.type == "toggle" ? (n.checked ? "togglepower" : "power") : (n.checked ? "checkmark.square.fill" : "square")
            view = AnyView(HStack(spacing: 8) {
                if n.type == "toggle" {
                    Capsule().fill(n.checked ? accent : Color.gray).frame(width: 40, height: 22)
                        .overlay(Circle().fill(Color.white).padding(3), alignment: n.checked ? .trailing : .leading)
                } else {
                    Image(systemName: symbol).foregroundColor(accent)
                        .font(.system(size: CGFloat(n.height > 0 ? n.height : 18)))
                }
                if !n.text.isEmpty {
                    Text(n.text).font(.system(size: CGFloat(n.fontSize > 0 ? n.fontSize : 14)))
                        .foregroundColor(n.checked && n.type == "checkbox" ? .secondary : color).lineLimit(1)
                }
            })
        case "button":
            view = AnyView(Text(n.text).font(.system(size: CGFloat(n.fontSize > 0 ? n.fontSize : 13), weight: .bold))
                .foregroundColor(HSWStyle.color(n.color, .white)).lineLimit(1)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(HSWStyle.background(n.background.isEmpty ? "accent" : n.background, radius: 12)))
        case "clock":
            let size = CGFloat(n.fontSize > 0 ? n.fontSize : 28)
            let fmt = n.text.lowercased()
            let isDate = fmt.contains("d") || fmt.contains("e") || fmt.contains("m") && !fmt.contains(":")
            view = AnyView(Group {
                if isDate {
                    Text(Date(), style: .date)
                } else {
                    Text(Date(), style: .time)
                }
            }.font(.system(size: size, weight: n.bold ? .bold : .regular)).foregroundColor(color))
        case "spacer":
            if n.width > 0 || n.height > 0 {
                view = AnyView(Color.clear.frame(width: n.width > 0 ? CGFloat(n.width) : nil, height: n.height > 0 ? CGFloat(n.height) : nil))
            } else {
                view = AnyView(Spacer(minLength: 0))
            }
        default: // text, icon
            let size = CGFloat(n.fontSize > 0 ? n.fontSize : (n.type == "icon" ? 20 : 13))
            view = AnyView(Text(n.text).font(.system(size: size, weight: n.bold ? .bold : .regular))
                .foregroundColor(color).lineLimit(3)
                .multilineTextAlignment(n.align == "center" ? .center : (n.align == "end" || n.align == "right" ? .trailing : .leading)))
        }

        if n.padding > 0 { view = AnyView(view.padding(CGFloat(n.padding))) }
        if !isTop && !n.background.isEmpty && n.type != "button" && n.type != "bar" && n.type != "checkbox" && n.type != "toggle" {
            view = AnyView(view.background(HSWStyle.background(n.background, radius: 14)))
        }
        if n.hasAction { view = action(view, n) }
        return view
    }

    private func weighted(_ v: AnyView, _ n: HSWNode, horizontal: Bool) -> AnyView {
        guard n.weight > 0 else { return v }
        return horizontal ? AnyView(v.frame(maxWidth: .infinity, alignment: HSWStyle.frameAlign(n.align)))
                          : AnyView(v.frame(maxHeight: .infinity, alignment: HSWStyle.frameAlign(n.align)))
    }

    private func actionDict(_ n: HSWNode) -> [String: Any] {
        let dl = (n.raw["DeepLink"] as? String) ?? ""
        return ["ActionType": n.raw["ActionType"] as? String ?? "openApp",
                "ActionName": n.raw["ActionName"] as? String ?? "",
                "RowId": n.id, "ButtonId": n.id,
                "Payload": n.raw["Payload"] as? String ?? "",
                "Url": n.raw["Url"] as? String ?? "",
                "Method": n.raw["Method"] as? String ?? "",
                "Body": n.raw["Body"] as? String ?? "",
                "DeepLink": dl.isEmpty ? data.deepLink : dl]
    }

    private func action(_ v: AnyView, _ n: HSWNode) -> AnyView {
        let type = (n.raw["ActionType"] as? String) ?? ""
        if type.caseInsensitiveCompare("http") == .orderedSame {
            if #available(iOSApplicationExtension 17.0, *) {
                return AnyView(Button(intent: HSWHttpIntent(url: n.raw["Url"] as? String ?? "",
                                                            method: n.raw["Method"] as? String ?? "",
                                                            body: n.raw["Body"] as? String ?? "")) { v }
                    .buttonStyle(.plain))
            }
        }
        if let url = data.actionURL(actionDict(n)) {
            return AnyView(Link(destination: url) { v })
        }
        return v
    }
}
